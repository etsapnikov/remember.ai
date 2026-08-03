"""FastAPI-бэкенд «Принял». Stateless: не хранит ни аудио, ни транскриптов —
источник истины живёт на телефоне (PRD §2).
"""

from __future__ import annotations

import logging
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from .asr import build_asr
from .audio import ffmpeg_available
from .config import Settings, get_settings
from .pipeline import IngestFailure, run_ingest
from .prompt import PROMPT_VERSION
from .schemas import ErrorResponse, IngestResponse

logger = logging.getLogger("prinyal")

_state: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    settings.require_token()
    if not ffmpeg_available():
        raise RuntimeError("ffmpeg не найден — конвейер /ingest работать не будет")

    asr = build_asr(settings.asr_backend, settings.asr_model_path)
    warmup = getattr(asr, "warmup", None)
    if callable(warmup):
        # Первый реальный запрос не должен платить за загрузку весов — иначе
        # замер латентности §8 покажет не то, что мы измеряем.
        warmup()

    _state["asr"] = asr
    _state["http"] = httpx.AsyncClient()
    _state["started_at"] = time.time()
    logger.info(
        "принял: ASR=%s, VAD=%s, LLM=%s", asr.name, settings.vad_backend, settings.llm_model
    )
    try:
        yield
    finally:
        await _state["http"].aclose()
        _state.clear()


app = FastAPI(title="Принял · backend", version="1.0", lifespan=lifespan)


def require_token(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    expected = settings.api_token
    supplied = ""
    if authorization and authorization.lower().startswith("bearer "):
        supplied = authorization[7:].strip()
    if not expected or supplied != expected:
        raise HTTPException(status_code=401, detail="unauthorized")


@app.get("/health")
async def health(settings: Settings = Depends(get_settings)) -> dict:
    asr = _state.get("asr")
    return {
        "ok": True,
        "asr": getattr(asr, "name", settings.asr_backend),
        "asr_mode": getattr(asr, "mode", ""),
        "vad": settings.vad_backend,
        "llm": settings.llm_model if settings.llm_enabled else None,
        "prompt_version": PROMPT_VERSION,
        "uptime_s": int(time.time() - _state.get("started_at", time.time())),
    }


@app.post(
    "/ingest",
    response_model=IngestResponse,
    responses={422: {"model": ErrorResponse}, 503: {"model": ErrorResponse}},
    dependencies=[Depends(require_token)],
)
async def ingest(
    audio: UploadFile = File(...),
    note_id: str = Form(...),
    client_ts: int | None = Form(default=None),
    tz_offset_minutes: int = Form(default=0),
    settings: Settings = Depends(get_settings),
) -> IngestResponse:
    data = await audio.read()
    if len(data) > settings.max_audio_bytes:
        raise HTTPException(status_code=413, detail="audio_too_long")

    try:
        response = await run_ingest(
            note_id=note_id,
            audio=data,
            tz_offset_minutes=tz_offset_minutes,
            client_ts=client_ts,
            settings=settings,
            asr=_state["asr"],
            http=_state["http"],
        )
    except IngestFailure as failure:
        logger.info("ingest %s → %s (%s)", note_id, failure.code, failure.detail)
        return JSONResponse(
            status_code=failure.status,
            content=ErrorResponse(error=failure.code, detail=failure.detail).model_dump(),
        )

    meta = response.meta
    # Логируем только цифры: ни транскрипта, ни айтемов (PRD §2 — stateless).
    logger.info(
        "ingest %s → items=%d asr=%dms llm=%dms retries=%d degraded=%s",
        note_id,
        len(response.items),
        meta.asr_ms,
        meta.llm_ms,
        meta.llm_retries,
        meta.degraded,
    )
    return response
