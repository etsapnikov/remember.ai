"""Конвейер /ingest: аудио → ffmpeg → VAD → GigaAM → DeepSeek → айтемы (PRD §F-4)."""

from __future__ import annotations

import asyncio
import time
from datetime import datetime, timedelta, timezone

import httpx

from . import vad
from .asr import Asr, AsrError
from .audio import AudioError, Pcm, decode_to_pcm
from .config import Settings
from .llm import LlmDegraded, LlmItems, parse_transcript, prompt_version
from .schemas import IngestMeta, IngestResponse
from .validate import fallback_items, validate_items


class IngestFailure(Exception):
    """Ошибка, после которой айтемов нет вовсе — приложение решает по коду, ретраить ли."""

    def __init__(self, code: str, detail: str = "", status: int = 502) -> None:
        super().__init__(detail or code)
        self.code = code
        self.detail = detail
        self.status = status


async def run_ingest(
    *,
    note_id: str,
    audio: bytes,
    tz_offset_minutes: int,
    client_ts: int | None,
    settings: Settings,
    asr: Asr,
    http: httpx.AsyncClient,
) -> IngestResponse:
    try:
        pcm = await decode_to_pcm(audio)
    except AudioError as exc:
        raise IngestFailure("audio_bad", str(exc), status=400) from exc

    if pcm.duration_s > settings.max_audio_seconds:
        raise IngestFailure(
            "audio_too_long",
            f"{pcm.duration_s:.0f} с при потолке {settings.max_audio_seconds:.0f} с",
            status=413,
        )

    asr_started = time.perf_counter()
    segments = vad.segment(pcm, settings.vad_backend, settings.vad_min_split_s)
    try:
        transcript = await _transcribe_all(pcm, segments, asr, settings.asr_timeout_s)
    except AsrError as exc:
        raise IngestFailure("asr_failed", str(exc), status=503) from exc
    except asyncio.TimeoutError as exc:
        raise IngestFailure("asr_failed", "ASR не уложился в таймаут", status=503) from exc
    asr_ms = int((time.perf_counter() - asr_started) * 1000)

    if not transcript.strip():
        # Тишина или не расслышал: аудио цело, ретраить автоматически бессмысленно.
        raise IngestFailure("asr_empty", "речь не распознана", status=422)

    now_local = _now_local(client_ts, tz_offset_minutes)

    llm_started = time.perf_counter()
    outcome = await parse_transcript(transcript, now_local, settings, http)
    llm_ms = int((time.perf_counter() - llm_started) * 1000)

    items, salvaged, degraded, retries = _items_from(
        outcome, transcript, now_local, tz_offset_minutes
    )

    meta = IngestMeta(
        asr_ms=asr_ms,
        llm_ms=llm_ms,
        llm_retries=retries,
        asr_backend=getattr(asr, "name", "unknown"),
        llm_model=settings.llm_model if settings.llm_enabled else None,
        prompt_version=prompt_version(),
        segments=len(segments),
        duration_ms=pcm.duration_ms,
        degraded=degraded,
        salvaged=salvaged,
    )
    return IngestResponse(note_id=note_id, transcript=transcript, items=items, meta=meta)


async def run_parse(
    *,
    note_id: str,
    transcript: str,
    tz_offset_minutes: int,
    client_ts: int | None,
    settings: Settings,
    http: httpx.AsyncClient,
) -> IngestResponse:
    """Только LLM-стадия: транскрипт уже есть (распознали на устройстве)."""
    now_local = _now_local(client_ts, tz_offset_minutes)

    llm_started = time.perf_counter()
    outcome = await parse_transcript(transcript, now_local, settings, http)
    llm_ms = int((time.perf_counter() - llm_started) * 1000)

    items, salvaged, degraded, retries = _items_from(
        outcome, transcript, now_local, tz_offset_minutes
    )

    meta = IngestMeta(
        asr_ms=0,
        llm_ms=llm_ms,
        llm_retries=retries,
        asr_backend="on_device",
        llm_model=settings.llm_model if settings.llm_enabled else None,
        prompt_version=prompt_version(),
        segments=0,
        duration_ms=0,
        degraded=degraded,
        salvaged=salvaged,
    )
    return IngestResponse(note_id=note_id, transcript=transcript, items=items, meta=meta)


def _items_from(outcome, transcript: str, now_local, tz_offset_minutes: int):
    """Общая для обоих входов развилка «разобрали / деградировали»."""
    if isinstance(outcome, LlmItems):
        result = validate_items(outcome.raw_items, transcript, now_local, tz_offset_minutes)
        if result.items:
            return result.items, result.salvaged, None, outcome.retries
        # Модель ответила, но пунктов не нашла — запись всё равно не теряем.
        return fallback_items(transcript), 0, "llm_empty", outcome.retries

    assert isinstance(outcome, LlmDegraded)
    return fallback_items(transcript), 0, outcome.reason, outcome.retries


async def _transcribe_all(
    pcm: Pcm, segments: list[vad.Segment], asr: Asr, timeout_s: float
) -> str:
    """Сегменты идут последовательно: на 8 vCPU параллельный GigaAM конкурирует сам
    с собой, а клип R1 — это 1–3 сегмента."""
    parts: list[str] = []
    async with asyncio.timeout(timeout_s):
        for segment in segments:
            chunk = pcm.slice_s(segment.start_s, segment.end_s)
            if chunk.duration_s < 0.2:
                continue
            text = (await asr.transcribe(chunk)).strip()
            if text:
                parts.append(text)
    return " ".join(parts)


def _now_local(client_ts: int | None, tz_offset_minutes: int) -> datetime:
    """Время считаем от момента нажатия на телефоне: между записью и разбором может
    лежать ночь оффлайна, и «завтра утром» из очереди должно быть завтра для записи."""
    base = datetime.fromtimestamp(client_ts, tz=timezone.utc) if client_ts else datetime.now(
        tz=timezone.utc
    )
    return (base + timedelta(minutes=tz_offset_minutes)).replace(tzinfo=None)
