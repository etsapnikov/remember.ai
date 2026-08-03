"""Инварианты PRD §3: код не доверяет модели, но и не теряет запись пользователя."""

from __future__ import annotations

from datetime import datetime

from app.validate import fallback_items, trim_words, validate_items

NOW = datetime(2026, 8, 3, 14, 0)  # понедельник
TRANSCRIPT = "капли купить соню к лору записать и мужу сказать что суббота занята"


def _one(raw: dict, transcript: str = TRANSCRIPT):
    result = validate_items([raw], transcript, NOW, tz_offset_minutes=180)
    return result.items[0], result.salvaged


def test_trim_by_word_not_mid_word():
    text = "очень длинная формулировка " * 10
    trimmed = trim_words(text)
    assert len(trimmed) <= 121
    assert not trimmed.rstrip("…").endswith(" ")
    assert "формулировк" not in trimmed[-4:]


def test_unknown_type_falls_to_thought_and_is_kept():
    item, salvaged = _one(
        {"type": "urgent_task", "text": "купить капли", "due_kind": "window",
         "window": "day", "confidence": "high"}
    )
    assert item.type == "thought"
    assert item.text == "купить капли"
    assert salvaged == 1


def test_unknown_confidence_becomes_low_not_high():
    item, _ = _one(
        {"type": "do", "text": "купить капли", "due_kind": "none", "confidence": "уверен"}
    )
    assert item.confidence == "low"


def test_who_dropped_when_it_did_not_sound():
    item, _ = _one(
        {"type": "tell", "text": "сказать про субботу", "who": "Марина",
         "due_kind": "window", "window": "evening", "confidence": "high"}
    )
    assert item.who is None


def test_who_kept_when_it_sounded_in_another_case():
    item, _ = _one(
        {"type": "tell", "text": "сказать что суббота занята", "who": "муж",
         "due_kind": "window", "window": "evening", "confidence": "high"}
    )
    assert item.who == "муж"


def test_past_exact_is_not_scheduled_in_the_past():
    item, salvaged = _one(
        {"type": "do", "text": "позвонить", "due_kind": "exact",
         "exact_local": "2026-08-03T09:00", "window": "day", "confidence": "high"}
    )
    assert item.due_at is None
    assert item.due_kind == "window"  # откатились в окно, а не выбросили
    assert salvaged == 1


def test_future_exact_becomes_unixtime_in_client_timezone():
    item, _ = _one(
        {"type": "do", "text": "стоматолог", "due_kind": "exact",
         "exact_local": "2026-08-07T19:00", "confidence": "high"}
    )
    assert item.due_at == int(datetime(2026, 8, 7, 19, 0).timestamp()) - 180 * 60
    assert item.window is None


def test_absurd_horizon_rejected():
    item, _ = _one(
        {"type": "date", "text": "через сто лет", "due_kind": "exact",
         "exact_local": "2130-01-01T10:00", "confidence": "medium"}
    )
    assert item.due_at is None


def test_bad_window_falls_to_none_not_to_a_guess():
    item, _ = _one(
        {"type": "do", "text": "заехать в банк", "due_kind": "window",
         "window": "после обеда", "confidence": "medium"}
    )
    assert (item.due_kind, item.window) == ("none", None)


def test_fact_is_never_scheduled():
    item, _ = _one(
        {"type": "fact", "text": "паспорта в синей коробке", "due_kind": "window",
         "window": "evening", "confidence": "high"},
        transcript="паспорта лежат в синей коробке",
    )
    assert (item.due_kind, item.window, item.due_at) == ("none", None, None)


def test_angle_brackets_cannot_reach_the_notification():
    item, _ = _one(
        {"type": "do", "text": "<b>купить</b> капли", "due_kind": "none", "confidence": "high"}
    )
    assert "<" not in item.text and ">" not in item.text


def test_item_without_text_is_dropped_but_others_survive():
    result = validate_items(
        [
            {"type": "do", "text": "", "due_kind": "none", "confidence": "high"},
            {"type": "buy", "text": "капли", "due_kind": "window", "window": "day",
             "confidence": "high"},
        ],
        TRANSCRIPT,
        NOW,
        180,
    )
    assert [i.text for i in result.items] == ["капли"]


def test_fallback_keeps_whole_transcript_as_one_thought():
    items = fallback_items(TRANSCRIPT)
    assert len(items) == 1
    assert items[0].type == "thought"
    assert items[0].window == "evening"
    assert items[0].confidence == "low"
