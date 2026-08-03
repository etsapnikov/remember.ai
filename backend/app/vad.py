"""Сегментация длинного клипа перед распознаванием (PRD §2.1).

Для клипа ≤ 90 с обычно получается 1–3 сегмента; короткие клипы не режем вовсе —
склейка на границе слова стоит дороже, чем выигрыш от параллелизма.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .audio import Pcm

# Пауза короче этой — внутри фразы, а не между фразами.
_MERGE_GAP_S = 0.45
# Хвосты вокруг найденной речи: ASR теряет первый слог, если резать вплотную.
_PAD_S = 0.25
_FRAME_MS = 30


@dataclass(frozen=True)
class Segment:
    start_s: float
    end_s: float

    @property
    def duration_s(self) -> float:
        return self.end_s - self.start_s


def _frame_rms(pcm: Pcm, frame_len: int) -> list[float]:
    samples = pcm.samples
    out: list[float] = []
    for start in range(0, len(samples), frame_len):
        chunk = samples[start : start + frame_len]
        if not chunk:
            break
        acc = 0
        for value in chunk:
            acc += value * value
        out.append(math.sqrt(acc / len(chunk)))
    return out


def _merge(spans: list[Segment], total_s: float) -> list[Segment]:
    if not spans:
        return []
    merged = [spans[0]]
    for span in spans[1:]:
        last = merged[-1]
        if span.start_s - last.end_s <= _MERGE_GAP_S:
            merged[-1] = Segment(last.start_s, span.end_s)
        else:
            merged.append(span)
    return [
        Segment(max(0.0, s.start_s - _PAD_S), min(total_s, s.end_s + _PAD_S)) for s in merged
    ]


def energy_segments(pcm: Pcm) -> list[Segment]:
    """Амплитудный VAD: порог считается от самого клипа, а не от абсолютной константы,
    иначе тихая запись в кровати и запись на улице требуют разных настроек."""
    frame_len = int(pcm.sample_rate * _FRAME_MS / 1000)
    rms = _frame_rms(pcm, frame_len)
    if not rms:
        return []

    ordered = sorted(rms)
    noise = ordered[len(ordered) // 10]  # 10-й перцентиль — шумовой пол
    peak = ordered[int(len(ordered) * 0.95)]
    if peak <= noise * 1.5:
        # Ровный уровень: либо сплошная речь, либо сплошная тишина — не режем.
        return [Segment(0.0, pcm.duration_s)]
    threshold = noise + (peak - noise) * 0.18

    spans: list[Segment] = []
    start: int | None = None
    for index, value in enumerate(rms):
        if value >= threshold:
            if start is None:
                start = index
        elif start is not None:
            spans.append(Segment(start * _FRAME_MS / 1000, index * _FRAME_MS / 1000))
            start = None
    if start is not None:
        spans.append(Segment(start * _FRAME_MS / 1000, len(rms) * _FRAME_MS / 1000))

    return _merge(spans, pcm.duration_s)


def silero_segments(pcm: Pcm) -> list[Segment]:
    """Silero VAD. Тянет torch, поэтому импорт ленивый: контейнер без ASR-слоя
    не обязан нести полкило зависимостей."""
    import torch  # noqa: PLC0415
    from silero_vad import get_speech_timestamps, load_silero_vad  # noqa: PLC0415

    model = _load_silero(load_silero_vad)
    tensor = torch.tensor([s / 32768.0 for s in pcm.samples], dtype=torch.float32)
    stamps = get_speech_timestamps(
        tensor, model, sampling_rate=pcm.sample_rate, return_seconds=True
    )
    spans = [Segment(float(s["start"]), float(s["end"])) for s in stamps]
    return _merge(spans, pcm.duration_s)


_silero_model = None


def _load_silero(loader):  # pragma: no cover - требует весов
    global _silero_model
    if _silero_model is None:
        _silero_model = loader()
    return _silero_model


def segment(pcm: Pcm, backend: str, min_split_s: float) -> list[Segment]:
    """Возвращает хотя бы один сегмент всегда: пустой список означал бы «нечего
    распознавать», а это решение принимает ASR-стадия, не VAD."""
    if pcm.duration_s <= min_split_s:
        return [Segment(0.0, pcm.duration_s)]

    try:
        spans = silero_segments(pcm) if backend == "silero" else energy_segments(pcm)
    except Exception:  # noqa: BLE001 - VAD не имеет права ронять конвейер
        spans = []

    spans = [s for s in spans if s.duration_s >= 0.2]
    return spans or [Segment(0.0, pcm.duration_s)]
