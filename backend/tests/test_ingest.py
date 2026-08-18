"""Сквозная приёмка /ingest (PRD §F-4, §F-5): настоящий ffmpeg, настоящий VAD,
стаб-ASR и замоканный DeepSeek.
"""

from __future__ import annotations

import os

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app.main import app

from .conftest import TOKEN, deepseek_reply, silence_wav, speech_like_wav, to_m4a

URL = "https://api.deepseek.com/chat/completions"

# Сценарий приёмки из PRD §F-5: три пункта с разными планами.
KOMOK_ITEMS = [
    {"type": "buy", "text": "капли", "who": None, "due_kind": "window", "window": "day",
     "confidence": "high", "raw_span": "капли купить"},
    {"type": "do", "text": "записать соню к лору", "who": None, "due_kind": "window",
     "window": "morning", "confidence": "high", "raw_span": "соню к лору записать"},
    {"type": "tell", "text": "сказать что суббота занята", "who": "муж",
     "due_kind": "window", "window": "evening", "confidence": "high",
     "raw_span": "мужу сказать что суббота занята"},
]


def post(client: TestClient, audio_bytes: bytes, **form):
    payload = {"note_id": "01J0TEST", "client_ts": "1754222400", "tz_offset_minutes": "180"}
    payload.update({k: str(v) for k, v in form.items()})
    return client.post(
        "/ingest",
        headers={"Authorization": f"Bearer {TOKEN}"},
        files={"audio": ("clip.m4a", audio_bytes, "audio/mp4")},
        data=payload,
    )


@pytest.fixture
def client():
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def m4a():
    return to_m4a(speech_like_wav(3.0))


def test_rejects_missing_token(client, m4a):
    response = client.post(
        "/ingest",
        files={"audio": ("clip.m4a", m4a, "audio/mp4")},
        data={"note_id": "x"},
    )
    assert response.status_code == 401


@respx.mock
def test_komok_becomes_three_items_with_different_plans(client, m4a):
    os.environ["ASR_STUB_TEXT"] = (
        "капли купить соню к лору записать и мужу сказать что суббота занята"
    )
    respx.post(URL).mock(return_value=httpx.Response(200, json=deepseek_reply(KOMOK_ITEMS)))

    response = post(client, m4a)
    assert response.status_code == 200
    body = response.json()

    assert len(body["items"]) == 3
    assert {i["type"] for i in body["items"]} == {"buy", "do", "tell"}
    # Разные планы — это и есть «доказательство понимания», а не список из трёх «day».
    assert len({i["window"] for i in body["items"]}) == 3
    assert body["items"][2]["who"] == "муж"
    assert all(i["raw_span"] for i in body["items"])
    assert body["meta"]["degraded"] is None
    assert body["meta"]["asr_backend"] == "stub"
    assert body["note_id"] == "01J0TEST"


@respx.mock
def test_counter_scenario_keeps_low_confidence_visible(client, m4a):
    os.environ["ASR_STUB_TEXT"] = "срочно counter клиенту по цене"
    respx.post(URL).mock(
        return_value=httpx.Response(
            200,
            json=deepseek_reply([
                {"type": "do", "text": "counter клиенту по цене", "due_kind": "window",
                 "window": "day", "confidence": "low", "raw_span": "counter клиенту по цене"},
            ]),
        )
    )
    body = post(client, m4a).json()
    item = body["items"][0]
    assert (item["due_kind"], item["window"]) == ("window", "day")
    assert item["confidence"] == "low"


@respx.mock
def test_llm_failure_degrades_to_one_thought_not_to_an_error(client, m4a):
    """Петля деградирует, но не рвётся (PRD §6)."""
    os.environ["ASR_STUB_TEXT"] = "надо не забыть про капли и про соню"
    respx.post(URL).mock(return_value=httpx.Response(500))

    response = post(client, m4a)
    assert response.status_code == 200
    body = response.json()
    assert body["meta"]["degraded"] == "llm_error"
    assert len(body["items"]) == 1
    assert body["items"][0]["type"] == "thought"
    assert body["items"][0]["window"] == "evening"
    assert body["items"][0]["text"] == "надо не забыть про капли и про соню"


@respx.mock
def test_no_balance_has_its_own_code_for_the_app(client, m4a):
    os.environ["ASR_STUB_TEXT"] = "купить капли"
    respx.post(URL).mock(return_value=httpx.Response(402))
    body = post(client, m4a).json()
    assert body["meta"]["degraded"] == "llm_no_balance"
    assert len(body["items"]) == 1


@respx.mock
def test_model_found_nothing_still_keeps_the_note(client, m4a):
    os.environ["ASR_STUB_TEXT"] = "мгм ну да ладно"
    respx.post(URL).mock(return_value=httpx.Response(200, json=deepseek_reply([])))
    body = post(client, m4a).json()
    assert len(body["items"]) == 1
    assert body["items"][0]["type"] == "thought"


def test_silence_is_asr_empty_not_a_retryable_failure(client):
    response = post(client, to_m4a(silence_wav(2.0)))
    assert response.status_code == 422
    assert response.json()["error"] == "asr_empty"


def test_broken_payload_is_rejected_without_retry(client):
    response = post(client, b"this is not audio at all")
    assert response.status_code == 400
    assert response.json()["error"] == "audio_bad"


@respx.mock
def test_meta_carries_the_numbers_analytics_needs(client, m4a):
    os.environ["ASR_STUB_TEXT"] = "купить капли"
    respx.post(URL).mock(return_value=httpx.Response(200, json=deepseek_reply([KOMOK_ITEMS[0]])))
    meta = post(client, m4a).json()["meta"]
    assert meta["asr_ms"] >= 0 and meta["llm_ms"] >= 0
    assert meta["duration_ms"] == pytest.approx(3000, abs=250)
    assert meta["prompt_version"] == "6"
    assert meta["segments"] >= 1


def test_health_reports_the_stack(client):
    body = client.get("/health").json()
    assert body["ok"] is True
    assert body["asr"] == "stub"
    assert body["prompt_version"] == "6"


# --- путь для ASR на устройстве ---


def post_parse(client: TestClient, transcript: str, **extra):
    payload = {
        "note_id": "01J0PARSE",
        "transcript": transcript,
        "client_ts": 1754222400,
        "tz_offset_minutes": 180,
    }
    payload.update(extra)
    return client.post(
        "/parse", headers={"Authorization": f"Bearer {TOKEN}"}, json=payload
    )


def test_parse_needs_a_token(client):
    response = client.post("/parse", json={"note_id": "x", "transcript": "текст"})
    assert response.status_code == 401


@respx.mock
def test_parse_turns_transcript_into_items_without_touching_audio(client):
    respx.post(URL).mock(return_value=httpx.Response(200, json=deepseek_reply(KOMOK_ITEMS)))

    response = post_parse(
        client, "капли купить соню к лору записать и мужу сказать что суббота занята"
    )
    assert response.status_code == 200
    body = response.json()

    assert len(body["items"]) == 3
    assert len({i["window"] for i in body["items"]}) == 3
    # ASR не участвовал: аудио телефон не покидал.
    assert body["meta"]["asr_backend"] == "on_device"
    assert body["meta"]["asr_ms"] == 0
    assert body["meta"]["degraded"] is None


@respx.mock
def test_parse_degrades_the_same_way_as_ingest(client):
    respx.post(URL).mock(return_value=httpx.Response(500))
    body = post_parse(client, "надо не забыть про капли").json()
    assert body["meta"]["degraded"] == "llm_error"
    assert len(body["items"]) == 1
    assert body["items"][0]["type"] == "thought"


def test_parse_rejects_empty_transcript(client):
    response = post_parse(client, "   ")
    assert response.status_code == 422
    assert response.json()["error"] == "asr_empty"
