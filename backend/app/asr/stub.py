"""Стаб-ASR: держит контракт, пока не подключён GigaAM.

Выдаёт транскрипт в том же виде, в каком его отдаёт GigaAM — нижний регистр, без
пунктуации, — чтобы LLM-стадия и приложение проверялись на реалистичном входе, а не
на причёсанном тексте.
"""

from __future__ import annotations

import asyncio
import hashlib
import os

from ..audio import Pcm

# Комки из ТЗ и PRD §F-5 — на них же построена приёмка.
FIXTURES: tuple[str, ...] = (
    "капли купить соню к лору записать и мужу сказать что суббота занята",
    "срочно counter клиенту по цене надо сегодня же ответить",
    "не забыть утром выпить таблетки и вечером позвонить маме",
    "паспорта лежат в синей коробке на антресоли",
    "в пятницу в семь у стоматолога и по дороге забрать посылку",
)

# Ниже этого RMS считаем, что речи не было: случайное нажатие, карман, тишина.
_SILENCE_RMS = 40.0


class StubAsr:
    name = "stub"

    async def transcribe(self, pcm: Pcm) -> str:
        # Имитируем работу движка, чтобы замеры латентности в логах не были нулевыми.
        await asyncio.sleep(0.01)

        override = os.environ.get("ASR_STUB_TEXT")
        if override is not None:
            return override.strip()

        if _is_silent(pcm):
            return ""

        digest = hashlib.sha256(pcm.to_bytes()).digest()
        return FIXTURES[digest[0] % len(FIXTURES)]


def _is_silent(pcm: Pcm) -> bool:
    if not pcm.samples:
        return True
    acc = 0
    # Считаем по каждому 10-му сэмплу: точности хватает, а 90 с не жуём целиком.
    step = 10
    count = 0
    for index in range(0, len(pcm.samples), step):
        value = pcm.samples[index]
        acc += value * value
        count += 1
    rms = (acc / max(1, count)) ** 0.5
    return rms < _SILENCE_RMS
