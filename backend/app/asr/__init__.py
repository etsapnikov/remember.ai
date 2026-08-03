"""ASR-слой. Контракт один — «аудио → транскрипт», — поэтому перенос распознавания
со стаба на GigaAM, а позже на устройство (R2), не трогает конвейер (PRD §2, п. 2).
"""

from __future__ import annotations

from typing import Protocol

from ..audio import Pcm


class AsrError(RuntimeError):
    """Движок не смог распознать. Приложение получит `asr_failed` и ретраит."""


class Asr(Protocol):
    name: str

    async def transcribe(self, pcm: Pcm) -> str:
        """Нижний регистр, без пунктуации — норма для GigaAM.

        Нормализацию, пунктуацию и смысл делает LLM-стадия (PRD §2.1).
        """
        ...


def build_asr(backend: str, model_path: str) -> Asr:
    if backend == "gigaam":
        from .gigaam import GigaAmAsr  # noqa: PLC0415 - тяжёлые импорты только по нужде

        return GigaAmAsr(model_path)
    if backend == "stub":
        from .stub import StubAsr  # noqa: PLC0415

        return StubAsr()
    raise ValueError(f"неизвестный ASR_BACKEND: {backend}")
