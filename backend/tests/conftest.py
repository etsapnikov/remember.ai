from __future__ import annotations

import json
import math
import os
import struct
import subprocess
import wave
from io import BytesIO

import pytest

os.environ.setdefault("PRINYAL_API_TOKEN", "test-token")
os.environ.setdefault("ASR_BACKEND", "stub")
os.environ.setdefault("LLM_ENABLED", "1")
os.environ.setdefault("DEEPSEEK_API_KEY", "test-key")

TOKEN = os.environ["PRINYAL_API_TOKEN"]


def speech_like_wav(seconds: float = 3.0, sample_rate: int = 16_000) -> bytes:
    """Не тишина: амплитудно-модулированный тон даёт VAD и стабу что-то похожее на
    речь по энергии, оставаясь детерминированным."""
    frames = bytearray()
    total = int(seconds * sample_rate)
    for n in range(total):
        t = n / sample_rate
        envelope = 0.5 + 0.5 * math.sin(2 * math.pi * 2.5 * t)
        value = int(9000 * envelope * math.sin(2 * math.pi * 180 * t))
        frames += struct.pack("<h", value)

    buffer = BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(bytes(frames))
    return buffer.getvalue()


def silence_wav(seconds: float = 3.0, sample_rate: int = 16_000) -> bytes:
    buffer = BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(b"\x00\x00" * int(seconds * sample_rate))
    return buffer.getvalue()


def to_m4a(wav_bytes: bytes) -> bytes:
    """Телефон пишет AAC — проверяем конвейер на том же контейнере, а не на wav."""
    proc = subprocess.run(
        # frag_keyframe+empty_moov — иначе mp4-муксер требует seek и в пайп не пишет.
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "wav", "-i", "pipe:0",
         "-c:a", "aac", "-b:a", "64k", "-movflags", "frag_keyframe+empty_moov",
         "-f", "mp4", "pipe:1"],
        input=wav_bytes,
        capture_output=True,
        check=True,
    )
    return proc.stdout


def deepseek_reply(items: list[dict]) -> dict:
    return {
        "id": "chat-test",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": json.dumps({"items": items})},
                "finish_reason": "stop",
            }
        ],
        "usage": {"prompt_tokens": 1400, "completion_tokens": 400},
    }


@pytest.fixture
def audio() -> bytes:
    return speech_like_wav()


@pytest.fixture
def silence() -> bytes:
    return silence_wav()


@pytest.fixture(autouse=True)
def _clean_stub_text():
    yield
    os.environ.pop("ASR_STUB_TEXT", None)
