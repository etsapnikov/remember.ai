#!/usr/bin/env python3
"""Ярус 2: нужен ли ризонинг (пункт владельца от 21.08).

Разбор идёт около минуты, и владелец справедливо спрашивает, за что платит
ожиданием. Меряем на **тех же фикстурах**, по которым стенд проверяет разбор:
время и то, сходятся ли ожидания. Ответ на глаз здесь не годится — вопрос
стоит «выключить или оставить», и цена ошибки в обе стороны реальная.

    python3 scripts/eval_reasoning.py
"""
import json, pathlib, re, statistics, sys, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))
from app.prompt import SYSTEM_PROMPT, build_user_prompt  # noqa: E402
from datetime import datetime  # noqa: E402

KEY = next(m.group(1) for m in
           (re.match(r"DEEPSEEK_API_KEY=(.+)", l.strip())
            for l in (ROOT / "backend/.env").read_text().splitlines()) if m)


def ask(fixture, reasoning: bool):
    payload = {
        "model": "deepseek-v4-flash",
        "max_tokens": 16384,
        "temperature": 0.1,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(
                fixture["transcript"],
                datetime.fromisoformat(fixture["at"]),
                candidates=[tuple(c) for c in fixture.get("candidates", [])],
            )},
        ],
    }
    # Единственная разница между прогонами — рассуждения.
    #
    # Ключ именно `thinking: {"type": "disabled"}`. Первый заход использовал
    # `reasoning: {"max_tokens": 0}` — API молча его проигнорировал, модель
    # думала в обоих прогонах, и сравнение мерило шум. Поэтому в отчёт идёт
    # число токенов рассуждений: если оно не ноль, прогон не считается.
    if not reasoning:
        payload["thinking"] = {"type": "disabled"}
    req = urllib.request.Request(
        "https://api.deepseek.com/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"},
    )
    started = time.time()
    with urllib.request.urlopen(req, timeout=300) as r:
        body = json.load(r)
    took = time.time() - started
    content = body["choices"][0]["message"]["content"]
    usage = body.get("usage", {})
    think = usage.get("completion_tokens_details", {}).get("reasoning_tokens", 0) or 0
    return json.loads(content), took, think


def check(fixture, parsed):
    """Те же ожидания, что в ярусе 1: сходится или нет."""
    e = fixture["expect"]
    items = parsed.get("items", []) + (parsed.get("second") or {}).get("items", [])
    problems = []
    if "items_min" in e and len(items) < e["items_min"]:
        problems.append(f"пунктов {len(items)} < {e['items_min']}")
    if "items_max" in e and len(items) > e["items_max"]:
        problems.append(f"пунктов {len(items)} > {e['items_max']}")
    if e.get("note_kind") and parsed.get("note_kind") not in e["note_kind"]:
        problems.append(f"вид «{parsed.get('note_kind')}»")
    if "split" in e and bool(parsed.get("second")) != e["split"]:
        problems.append("разбиение")
    for t in e.get("types_none", []):
        if any(i.get("type") == t for i in items):
            problems.append(f"тип «{t}»")
    if e.get("types_any") and not any(i.get("type") in e["types_any"] for i in items):
        problems.append("нет нужного типа")
    if e.get("links_to") and (parsed.get("links") or [{}])[0].get("ref") != e["links_to"]:
        problems.append("связь не та")
    if e.get("links_empty") and parsed.get("links"):
        problems.append("выдумана связь")
    if e.get("note_due_date") and not parsed.get("note_exact_local", "").startswith(e["note_due_date"]):
        problems.append("общий срок")
    return problems


fixtures = sorted((ROOT / "fixtures/notes").glob("*.json"))
rows = []
for path in fixtures:
    f = json.loads(path.read_text())
    line = {"id": f["id"]}
    for mode, reasoning in (("с ризонингом", True), ("без", False)):
        try:
            parsed, took, think = ask(f, reasoning)
            line[mode] = (took, think, check(f, parsed))
        except Exception as exc:
            line[mode] = (0, 0, [f"ошибка: {type(exc).__name__}"])
    rows.append(line)
    a, b = line["с ризонингом"], line["без"]
    mark = lambda p: "ок" if not p[2] else "✗ " + "; ".join(p[2])
    print(f"{f['id']:22} ризонинг {a[0]:5.1f}с {a[1]:5}т {mark(a):28} | без {b[0]:5.1f}с {mark(b)}")

for mode in ("с ризонингом", "без"):
    times = [r[mode][0] for r in rows if r[mode][0]]
    bad = sum(1 for r in rows if r[mode][2])
    print(f"\n{mode}: медиана {statistics.median(times):.1f} с, "
          f"суммарно {sum(times):.0f} с, расхождений с ожиданиями: {bad} из {len(rows)}")
json.dump(rows, open(ROOT / "docs/eval-reasoning.json", "w"), ensure_ascii=False, indent=2)
