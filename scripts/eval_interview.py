#!/usr/bin/env python3
"""Ярус 2 для интервьюера (Р-15.14): три круга на живой модели.

Приёмка требует трёх разных конкретных вопросов подряд. Проверить это плёнкой
нельзя — смысл как раз в том, что модель не повторяется, — поэтому ярус живой.
Стоп-лист и проверка «вопрос про эту запись» повторяют InterviewPolicy.kt;
разойтись им нельзя, иначе eval проверяет не то правило.

    python3 scripts/eval_interview.py
"""
import json, pathlib, re, sys, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
GENERIC = ["что дальше", "какой первый шаг", "с чего начать", "что для этого нужно",
           "как ты это видишь", "что думаешь", "зачем тебе это", "какие есть варианты"]
IDEA = ("хочу сделать приложение которое собирает контекст по проекту из моих "
        "голосовых заметок и отдаёт его одним файлом перед встречей чтобы не "
        "листать переписку за месяц")

PROMPT = re.search(
    r'const val INTERVIEW = """(.*?)"""',
    (ROOT / "android/app/src/main/java/ai/prinim/prinyal/llm/Prompt.kt").read_text(),
    re.S,
).group(1).strip()


def key() -> str:
    for line in (ROOT / "backend" / ".env").read_text().splitlines():
        m = re.match(r"DEEPSEEK_API_KEY=(.+)", line.strip())
        if m:
            return m.group(1)
    raise SystemExit("ключа нет")


def ask(asked):
    user = f"Запись:\n{IDEA}\n"
    if asked:
        user += "\nУже спрашивали:\n" + "".join(f"  {q}\n" for q in asked)
    payload = {"model": "deepseek-v4-flash", "temperature": 0.4, "max_tokens": 16384,
               "messages": [{"role": "system", "content": PROMPT},
                            {"role": "user", "content": user}]}
    req = urllib.request.Request(
        "https://api.deepseek.com/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {key()}"},
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        body = json.load(r)
    return body["choices"][0]["message"]["content"].strip().strip('"«»')


def stems(text):
    return {w[:5] if len(w) > 6 else w for w in re.findall(r"[а-яёa-z0-9]+", text.lower())
            if len(w) >= 3}


def echoes(question, idea):
    qs, ids = stems(question), stems(idea)
    return sum(1 for w in qs if any(
        len(p := __import__("os").path.commonprefix([w, o])) >= 4 and p in (w, o) for o in ids))


asked, bad = [], 0
for round_no in range(1, 4):
    q = ask(asked)
    lower = q.lower()
    problems = []
    if not q.endswith("?"):
        problems.append("не вопрос")
    if any(g in lower for g in GENERIC):
        problems.append("генерик")
    if echoes(q, IDEA) < 1:
        problems.append("не про эту запись")
    # Повтор — только по словам, которых в идее не было: вопросы про одну идею
    # неизбежно делят её словарь, и сличение целиком браковало бы третий круг
    # подряд. То же правило, что в InterviewPolicy.kt.
    novel = stems(q) - stems(IDEA)
    if any(len(novel & (stems(a) - stems(IDEA))) >= 3 for a in asked):
        problems.append("повтор")
    bad += bool(problems)
    print(f"{round_no}. {q}")
    if problems:
        print(f"   ✗ {', '.join(problems)}")
    asked.append(q)

print(f"\nбраком признано {bad} из 3")
sys.exit(1 if bad else 0)
