"""LLM-стадия: ретраи и деградации §6 — то, что решает, порвётся петля или прогнётся."""

from __future__ import annotations

from datetime import datetime

import httpx
import pytest
import respx

from app.config import Settings
from app.llm import LlmDegraded, LlmItems, parse_transcript

from .conftest import deepseek_reply

NOW = datetime(2026, 8, 3, 14, 0)
URL = "https://api.deepseek.com/chat/completions"
ITEM = {
    "type": "do", "text": "позвонить в поликлинику", "due_kind": "window",
    "window": "day", "confidence": "high", "raw_span": "соню к лору",
}


def settings(**overrides) -> Settings:
    base = {
        "api_token": "t", "deepseek_api_key": "k", "llm_enabled": True,
        "llm_retries": 2, "llm_timeout_s": 5.0,
    }
    base.update(overrides)
    return Settings(**base)


@pytest.mark.asyncio
@respx.mock
async def test_happy_path():
    respx.post(URL).mock(return_value=httpx.Response(200, json=deepseek_reply([ITEM])))
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)
    assert isinstance(result, LlmItems)
    assert result.raw_items == [ITEM]
    assert result.retries == 0


@pytest.mark.asyncio
@respx.mock
async def test_empty_content_is_retried_then_succeeds():
    """Документированная особенность провайдера — воспроизводится тестом (§F-4)."""
    empty = {"choices": [{"message": {"role": "assistant", "content": ""}}]}
    route = respx.post(URL)
    route.side_effect = [
        httpx.Response(200, json=empty),
        httpx.Response(200, json=deepseek_reply([ITEM])),
    ]
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)
    assert isinstance(result, LlmItems)
    assert result.retries == 1


@pytest.mark.asyncio
@respx.mock
async def test_empty_content_all_attempts_degrades():
    empty = {"choices": [{"message": {"role": "assistant", "content": ""}}]}
    respx.post(URL).mock(return_value=httpx.Response(200, json=empty))
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)
    assert isinstance(result, LlmDegraded)
    assert result.reason == "llm_empty"
    assert result.retries == 2


@pytest.mark.asyncio
@respx.mock
async def test_token_limit_degrades_at_once_without_burning_two_more_rounds():
    """Пустой content из-за `finish_reason: length` воспроизводим — повтор даст ровно
    то же самое, только заставит человека ждать ещё два круга по 20 с."""
    capped = {
        "choices": [{"message": {"role": "assistant", "content": ""}, "finish_reason": "length"}]
    }
    route = respx.post(URL).mock(return_value=httpx.Response(200, json=capped))
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)

    assert isinstance(result, LlmDegraded)
    assert result.reason == "llm_empty"
    assert route.call_count == 1


@pytest.mark.asyncio
@respx.mock
async def test_402_is_not_retried_and_has_its_own_reason():
    route = respx.post(URL).mock(return_value=httpx.Response(402, json={"error": "no balance"}))
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)
    assert isinstance(result, LlmDegraded)
    assert result.reason == "llm_no_balance"
    assert route.call_count == 1


@pytest.mark.asyncio
@respx.mock
async def test_5xx_is_retried_then_degrades():
    route = respx.post(URL).mock(return_value=httpx.Response(503))
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)
    assert isinstance(result, LlmDegraded)
    assert result.reason == "llm_error"
    assert route.call_count == 3


@pytest.mark.asyncio
@respx.mock
async def test_timeout_degrades_with_its_own_reason():
    respx.post(URL).mock(side_effect=httpx.ReadTimeout("slow"))
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)
    assert isinstance(result, LlmDegraded)
    assert result.reason == "llm_timeout"


@pytest.mark.asyncio
@respx.mock
async def test_markdown_wrapped_json_is_accepted():
    wrapped = {"choices": [{"message": {"content": '```json\n{"items": []}\n```'}}]}
    respx.post(URL).mock(return_value=httpx.Response(200, json=wrapped))
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(), client)
    assert isinstance(result, LlmItems)
    assert result.raw_items == []


@pytest.mark.asyncio
async def test_toggle_off_degrades_without_touching_network():
    async with httpx.AsyncClient() as client:
        result = await parse_transcript("текст", NOW, settings(llm_enabled=False), client)
    assert isinstance(result, LlmDegraded)
    assert result.reason == "llm_disabled"


@pytest.mark.asyncio
@respx.mock
async def test_request_carries_json_mode_and_time_context():
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(__import__("json").loads(request.content))
        return httpx.Response(200, json=deepseek_reply([ITEM]))

    respx.post(URL).mock(side_effect=handler)
    async with httpx.AsyncClient() as client:
        await parse_transcript("капли купить", NOW, settings(), client)

    assert captured["response_format"] == {"type": "json_object"}
    assert captured["temperature"] == 0.1
    assert captured["stream"] is False
    # Рассуждения модели не выключаем (Р-13.6): они чинят типы, окна и имена,
    # искажённые распознаванием. Бюджет обязан вмещать их вместе с ответом —
    # при 2048 рассуждения съедали его целиком и content приходил пустым.
    assert "thinking" not in captured
    assert captured["max_tokens"] >= 8192
    system, user = captured["messages"]
    # Требование провайдера: слово «json» и пример структуры обязаны быть в промпте.
    assert "json" in system["content"].lower()
    assert "понедельник" in user["content"]
    assert "капли купить" in user["content"]
