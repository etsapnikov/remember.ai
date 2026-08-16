"""LLM-стадия: транскрипт → айтемы (PRD §2.2).

Один вызов, не два: вход крошечный, стримить нечего. Ключ живёт только здесь, на
бэкенде — в APK его нет (PRD §2, п. 3; §7).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import datetime

import httpx

from .config import Settings
from .prompt import PROMPT_VERSION, SYSTEM_PROMPT, build_user_prompt
from .schemas import Degraded


class LlmOutcome:
    """Результат стадии: либо сырые айтемы модели, либо код деградации §6."""


@dataclass(frozen=True)
class LlmItems(LlmOutcome):
    raw_items: list[dict]
    retries: int


@dataclass(frozen=True)
class LlmDegraded(LlmOutcome):
    reason: Degraded
    retries: int


async def parse_transcript(
    transcript: str,
    now_local: datetime,
    settings: Settings,
    client: httpx.AsyncClient,
) -> LlmOutcome:
    if not settings.llm_enabled or not settings.deepseek_api_key:
        # Тумблер F-8 или отсутствие ключа: продукт превращается в «голосовые с
        # вечерним возвратом» — честная минимальная петля без LLM.
        return LlmDegraded("llm_disabled", 0)

    payload = {
        "model": settings.llm_model,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(transcript, now_local)},
        ],
        "temperature": settings.llm_temperature,
        "max_tokens": settings.llm_max_tokens,
        "response_format": {"type": "json_object"},
        "stream": False,
        # Рассуждения включены: прогон корпуса 16.08 показал, что они чинят
        # типы, окна и искажённые распознаванием имена. Бюджет считается вместе
        # с ними — при 2048 ответа не оставалось вовсе.
    }
    headers = {
        "Authorization": f"Bearer {settings.deepseek_api_key}",
        "Content-Type": "application/json",
    }
    url = f"{settings.deepseek_base_url.rstrip('/')}/chat/completions"

    attempts = settings.llm_retries + 1
    last_reason: Degraded = "llm_error"

    for attempt in range(attempts):
        try:
            response = await client.post(
                url, json=payload, headers=headers, timeout=settings.llm_timeout_s
            )
        except httpx.TimeoutException:
            last_reason = "llm_timeout"
            continue
        except httpx.HTTPError:
            last_reason = "llm_error"
            continue

        if response.status_code == 402:
            # Баланс не лечится ретраем — отдельный текст пользователю (PRD §6).
            return LlmDegraded("llm_no_balance", attempt)
        if response.status_code == 401:
            return LlmDegraded("llm_error", attempt)
        if response.status_code >= 500 or response.status_code == 429:
            last_reason = "llm_error"
            continue
        if response.status_code >= 400:
            return LlmDegraded("llm_error", attempt)

        content, finish_reason = _content_of(response)
        if not content:
            last_reason = "llm_empty"
            if finish_reason == "length":
                # Модель упёрлась в потолок токенов. Это не случайность, а
                # воспроизводимый исход: повтор даст ровно то же самое, только
                # заставит человека ждать ещё два круга. Уходим в деградацию сразу.
                return LlmDegraded("llm_empty", attempt)
            # Документированная особенность DeepSeek: изредка пустой content.
            continue

        items = _items_of(content)
        if items is None:
            last_reason = "llm_error"
            continue

        return LlmItems(items, attempt)

    return LlmDegraded(last_reason, attempts - 1)


def _content_of(response: httpx.Response) -> tuple[str, str]:
    """Возвращает (content, finish_reason) — по второму видно, стоит ли ретраить."""
    try:
        body = response.json()
    except ValueError:
        return "", ""
    choices = body.get("choices") or []
    if not choices:
        return "", ""
    choice = choices[0]
    message = choice.get("message") or {}
    return (message.get("content") or "").strip(), (choice.get("finish_reason") or "")


def _items_of(content: str) -> list[dict] | None:
    """Модель обязана вернуть json_object, но обёртка в ```json``` встречается и у
    послушных провайдеров — снимаем её, прежде чем сдаваться."""
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
        text = text.strip()

    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return None

    if isinstance(parsed, list):
        parsed = {"items": parsed}
    if not isinstance(parsed, dict):
        return None

    items = parsed.get("items")
    if items is None:
        return []
    if not isinstance(items, list):
        return None
    return [item for item in items if isinstance(item, dict)]


def prompt_version() -> str:
    return PROMPT_VERSION
