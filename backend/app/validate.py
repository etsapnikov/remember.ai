"""Валидация айтемов. Принцип недоверия к модели: она предлагает семантику, решения
принимает код (PRD §4).

Главный инвариант: айтем с непрошедшей валидацией не выбрасывается, а падает в
`type=thought, due_kind=none`. Запись пользователя не теряется никогда (PRD §3).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from .schemas import (
    CONFIDENCES,
    ITEM_TYPES,
    MAX_ITEM_TEXT,
    WINDOWS,
    Item,
)

MAX_WHO = 40
MAX_RAW_SPAN = 200
# Дальше этого горизонта exact-время почти наверняка галлюцинация разбора.
MAX_EXACT_HORIZON = timedelta(days=370)


@dataclass(frozen=True)
class ValidationResult:
    items: list[Item]
    salvaged: int


def trim_words(text: str, limit: int = MAX_ITEM_TEXT) -> str:
    """Обрезка по слову: обрубленное посреди слова читается как баг, а не как лимит."""
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    cut = text[:limit]
    space = cut.rfind(" ")
    if space >= limit // 2:
        cut = cut[:space]
    return cut.rstrip(" ,.;:—-") + "…"


def sanitize(text: str) -> str:
    """HTML невозможен по построению: рендер идёт через текст, но угловые скобки
    убираем здесь, чтобы они не всплыли в нотификации Android (она HTML понимает)."""
    cleaned = text.replace("<", "").replace(">", "").replace("&", "и")
    return " ".join(cleaned.split())


def validate_items(
    raw_items: list[dict],
    transcript: str,
    now_local: datetime,
    tz_offset_minutes: int,
) -> ValidationResult:
    items: list[Item] = []
    salvaged = 0

    for raw in raw_items:
        item, was_salvaged = _validate_one(raw, transcript, now_local, tz_offset_minutes)
        if item is None:
            continue
        items.append(item)
        salvaged += int(was_salvaged)

    return ValidationResult(items, salvaged)


def _validate_one(
    raw: dict,
    transcript: str,
    now_local: datetime,
    tz_offset_minutes: int,
) -> tuple[Item | None, bool]:
    text = sanitize(str(raw.get("text") or "")).strip()
    if not text:
        # Пункт без текста — не пункт; терять при этом нечего, транскрипт цел.
        return None, False

    salvaged = False

    item_type = str(raw.get("type") or "").strip().lower()
    if item_type not in ITEM_TYPES:
        item_type = "thought"
        salvaged = True

    confidence = str(raw.get("confidence") or "").strip().lower()
    if confidence not in CONFIDENCES:
        # Неизвестная уверенность — не «high»: продукт не имеет права выглядеть
        # увереннее, чем он есть (ТЗ UI §2, п. 4).
        confidence = "low"
        salvaged = True

    who = raw.get("who")
    who_text: str | None = None
    if isinstance(who, str) and who.strip():
        candidate = sanitize(who)[:MAX_WHO]
        # `who` — только если адресат явно прозвучал в записи (PRD §3).
        who_text = candidate if _sounds_in(candidate, transcript) else None
        salvaged = salvaged or who_text is None

    due_kind = str(raw.get("due_kind") or "none").strip().lower()
    window = raw.get("window")
    window = str(window).strip().lower() if isinstance(window, str) else None
    due_at: int | None = None

    if due_kind == "exact":
        due_at = _parse_exact(raw, now_local, tz_offset_minutes)
        if due_at is None:
            # Время не разобралось — не выдумываем его, откатываемся в окно.
            due_kind = "window" if window in WINDOWS else "none"
            salvaged = True
        else:
            window = None
    if due_kind == "window":
        if window not in WINDOWS:
            due_kind = "none"
            window = None
            salvaged = True
    elif due_kind != "exact":
        due_kind = "none"
        window = None

    # Факт о мире не возвращается в R1 — он копится молча (PRD §11, п. 3).
    if item_type == "fact":
        due_kind, window, due_at = "none", None, None

    raw_span = raw.get("raw_span")
    span_text = None
    if isinstance(raw_span, str) and raw_span.strip():
        span_text = sanitize(raw_span)[:MAX_RAW_SPAN]

    item = Item(
        type=item_type,
        text=trim_words(text),
        who=who_text,
        due_kind=due_kind,
        window=window,
        due_at=due_at,
        confidence=confidence,
        raw_span=span_text,
    )
    return item, salvaged


def _sounds_in(who: str, transcript: str) -> bool:
    """Имя считается прозвучавшим, если его основа есть в транскрипте: модель
    склоняет («мужу» → «муж»), а транскрипт — сырец."""
    haystack = transcript.lower()
    for word in who.lower().split():
        stem = word[:4] if len(word) > 4 else word
        if stem and stem in haystack:
            return True
    return False


def _parse_exact(raw: dict, now_local: datetime, tz_offset_minutes: int) -> int | None:
    """Модель отдаёт местное «YYYY-MM-DDTHH:MM», unixtime считает код.

    Инвариант PRD §3: `due_at` только в будущем.
    """
    value = raw.get("exact_local") or raw.get("due_local") or raw.get("due_at")
    if value is None:
        return None

    parsed: datetime | None = None
    if isinstance(value, (int, float)):
        # Модель всё-таки прислала unixtime — принимаем, но проверяем на будущее.
        try:
            return _future_or_none(int(value), now_local, tz_offset_minutes)
        except (ValueError, OverflowError):
            return None

    if isinstance(value, str):
        text = value.strip().replace(" ", "T")
        for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M", "%Y-%m-%d"):
            try:
                parsed = datetime.strptime(text[: len(fmt) + 4], fmt)
                break
            except ValueError:
                continue

    if parsed is None:
        return None

    unixtime = int(parsed.timestamp()) - tz_offset_minutes * 60
    return _future_or_none(unixtime, now_local, tz_offset_minutes)


def _future_or_none(unixtime: int, now_local: datetime, tz_offset_minutes: int) -> int | None:
    now_unix = int(now_local.timestamp()) - tz_offset_minutes * 60
    if unixtime <= now_unix:
        return None
    if unixtime - now_unix > MAX_EXACT_HORIZON.total_seconds():
        return None
    return unixtime


def fallback_items(transcript: str) -> list[Item]:
    """Деградация §6: разбор не случился — вся запись становится одним `thought`.

    Окно назначает приложение (evening по правилу планировщика §4): бэкенд не знает
    про окна пользователя и не должен знать.
    """
    return [
        Item(
            type="thought",
            text=trim_words(sanitize(transcript)),
            who=None,
            due_kind="window",
            window="evening",
            due_at=None,
            confidence="low",
            raw_span=None,
        )
    ]
