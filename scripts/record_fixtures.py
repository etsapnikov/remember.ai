#!/usr/bin/env python3
"""Запись ответов DeepSeek на фикстуры — плёнка для яруса 1 (Р-15.16).

Зачем плёнка. В CI ответы модели должны быть одинаковыми от прогона к прогону:
иначе тест проверяет настроение модели, а не наш код, стоит денег и падает без
нашей вины. Живая модель проверяется отдельно, ярусом 2.

Плёнка привязана к версии промпта. Промпт поменяли — записи устарели молча:
replay продолжит проходить, проверяя вчерашний контракт. Поэтому версия пишется
в каждый файл, а тест на несовпадении падает и требует перезаписи.

    python3 scripts/record_fixtures.py            # записать недостающие
    python3 scripts/record_fixtures.py --force    # перезаписать все
"""

from __future__ import annotations

import json
import pathlib
import re
import sys
import time
import urllib.request
from datetime import datetime

ROOT = pathlib.Path(__file__).resolve().parent.parent
NOTES = ROOT / "fixtures" / "notes"
REPLIES = ROOT / "fixtures" / "llm-replies"
sys.path.insert(0, str(ROOT / "backend"))

from app.prompt import PROMPT_VERSION, SYSTEM_PROMPT, build_user_prompt  # noqa: E402

MODEL = "deepseek-v4-flash"
MAX_TOKENS = 16_384


def api_key() -> str:
    env = ROOT / "backend" / ".env"
    for line in env.read_text(encoding="utf-8").splitlines():
        found = re.match(r"DEEPSEEK_API_KEY=(.+)", line.strip())
        if found:
            return found.group(1)
    raise SystemExit("ключа нет в backend/.env")


def ask(transcript: str, now: datetime, key: str) -> dict:
    payload = {
        "model": MODEL,
        "max_tokens": MAX_TOKENS,
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "stream": False,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(transcript, now)},
        ],
    }
    request = urllib.request.Request(
        "https://api.deepseek.com/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"},
    )
    with urllib.request.urlopen(request, timeout=400) as response:
        return json.loads(response.read())


def main() -> None:
    force = "--force" in sys.argv
    key = api_key()
    REPLIES.mkdir(parents=True, exist_ok=True)

    written = skipped = 0
    for path in sorted(NOTES.glob("*.json")):
        fixture = json.loads(path.read_text(encoding="utf-8"))
        target = REPLIES / f"{fixture['id']}.json"

        if target.exists() and not force:
            recorded = json.loads(target.read_text(encoding="utf-8"))
            if recorded.get("prompt_version") == PROMPT_VERSION:
                skipped += 1
                continue

        started = time.time()
        body = ask(fixture["transcript"], datetime.fromisoformat(fixture["at"]), key)
        choice = body["choices"][0]
        took = time.time() - started

        target.write_text(
            json.dumps(
                {
                    "id": fixture["id"],
                    "prompt_version": PROMPT_VERSION,
                    "recorded_at": datetime.now().isoformat(timespec="seconds"),
                    "took_s": round(took, 1),
                    "finish_reason": choice["finish_reason"],
                    "usage": body.get("usage"),
                    # Тело кладём целиком: клиент разбирает именно ответ API,
                    # и подменять его упрощённой формой значило бы проверять
                    # не тот код, который работает в бою.
                    "response": body,
                },
                ensure_ascii=False,
                indent=1,
            )
            + "\n",
            encoding="utf-8",
        )
        written += 1
        print(f"  {fixture['id']}: {took:.0f} c · {choice['finish_reason']}")

    print(f"\nзаписано {written}, пропущено {skipped} (промпт версии {PROMPT_VERSION})")


if __name__ == "__main__":
    main()
