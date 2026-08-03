"""Контракт «аудио → транскрипт → айтемы» между приложением и бэкендом.

Имена полей повторяют модель данных PRD §3, чтобы приложение клало ответ в SQLite
без переименований.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

ItemType = Literal["buy", "do", "tell", "date", "thought", "fact"]
DueKind = Literal["none", "window", "exact"]
Window = Literal["morning", "day", "evening", "tomorrow_morning", "weekend"]
Confidence = Literal["high", "medium", "low"]

ITEM_TYPES: frozenset[str] = frozenset({"buy", "do", "tell", "date", "thought", "fact"})
WINDOWS: frozenset[str] = frozenset(
    {"morning", "day", "evening", "tomorrow_morning", "weekend"}
)
CONFIDENCES: frozenset[str] = frozenset({"high", "medium", "low"})

# Инвариант PRD §3: текст айтема обрезается по слову.
MAX_ITEM_TEXT = 120


class Item(BaseModel):
    type: ItemType
    text: str = Field(max_length=MAX_ITEM_TEXT)
    who: str | None = None
    due_kind: DueKind
    window: Window | None = None
    # unixtime; заполняет код по таймзоне клиента, не модель (PRD §4).
    due_at: int | None = None
    confidence: Confidence
    raw_span: str | None = None


# Причина деградации. Приложение переводит код в строку из strings.xml (PRD §4.1) —
# бэкенд не отдаёт пользовательских текстов, чтобы словарь остался единственным.
Degraded = Literal[
    "llm_empty",  # пустой content после ретраев
    "llm_error",  # 5xx / сеть / невалидный json
    "llm_timeout",
    "llm_no_balance",  # 402
    "llm_disabled",  # тумблер F-8 выключен на бэкенде
]


class IngestMeta(BaseModel):
    asr_ms: int
    llm_ms: int
    llm_retries: int = 0
    asr_backend: str
    llm_model: str | None = None
    prompt_version: str
    segments: int = 1
    duration_ms: int = 0
    degraded: Degraded | None = None
    # Сколько айтемов не прошло валидацию и упало в thought (PRD §3).
    salvaged: int = 0


class IngestResponse(BaseModel):
    note_id: str
    transcript: str
    items: list[Item]
    meta: IngestMeta


class ErrorResponse(BaseModel):
    """Ошибки, после которых айтемов нет вовсе.

    `asr_empty` — тишина/не расслышал, автоповтор бессмысленен;
    `asr_failed` — движок упал, приложение ретраит по backoff.
    """

    error: Literal["asr_empty", "asr_failed", "audio_bad", "audio_too_long", "unauthorized"]
    detail: str | None = None
