"""Приведение того, что записал телефон (AAC/OGG/M4A), к тому, что ест ASR:
16 kHz mono PCM (PRD §2.1).
"""

from __future__ import annotations

import array
import asyncio
import shutil
import subprocess
from dataclasses import dataclass

SAMPLE_RATE = 16_000


class AudioError(RuntimeError):
    """Файл не читается ffmpeg — битая или не-аудио полезная нагрузка."""


@dataclass(frozen=True)
class Pcm:
    """Моно 16-bit PCM на 16 kHz, как массив int16."""

    samples: array.array
    sample_rate: int = SAMPLE_RATE

    @property
    def duration_s(self) -> float:
        return len(self.samples) / float(self.sample_rate)

    @property
    def duration_ms(self) -> int:
        return int(round(self.duration_s * 1000))

    def slice_s(self, start_s: float, end_s: float) -> "Pcm":
        a = max(0, int(start_s * self.sample_rate))
        b = min(len(self.samples), int(end_s * self.sample_rate))
        return Pcm(self.samples[a:b], self.sample_rate)

    def to_bytes(self) -> bytes:
        return self.samples.tobytes()


def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None


async def decode_to_pcm(data: bytes) -> Pcm:
    """ffmpeg читает контейнер из stdin и отдаёт сырой s16le в stdout.

    Без временных файлов: бэкенд stateless, аудио на диск не ложится (PRD §2).
    """
    if not data:
        raise AudioError("пустое тело запроса")

    cmd = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel", "error",
        "-i", "pipe:0",
        "-f", "s16le",
        "-acodec", "pcm_s16le",
        "-ac", "1",
        "-ar", str(SAMPLE_RATE),
        "pipe:1",
    ]
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except FileNotFoundError as exc:  # pragma: no cover - окружение без ffmpeg
        raise AudioError("ffmpeg не найден в контейнере") from exc

    stdout, stderr = await proc.communicate(data)
    if proc.returncode != 0 or not stdout:
        tail = stderr.decode("utf-8", "replace").strip().splitlines()
        raise AudioError(tail[-1] if tail else "ffmpeg не смог декодировать аудио")

    samples = array.array("h")
    # Нечётный хвост байта означал бы обрезанный сэмпл — отбрасываем его.
    samples.frombytes(stdout[: len(stdout) - (len(stdout) % 2)])
    return Pcm(samples)
