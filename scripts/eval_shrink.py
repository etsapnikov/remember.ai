"""Ярус 2 для развилки (Р-15.8): даёт ли модель настоящий малый шаг."""
import json, pathlib, re, urllib.request
ROOT = pathlib.Path("/Users/etsapnikov/Documents/prinyal")
src = (ROOT/"android/app/src/main/java/ai/prinim/prinyal/llm/Prompt.kt").read_text()
SHRINK = re.search(r'const val SHRINK = """(.*?)"""', src, re.S).group(1).strip()
GENERIC = ["начни с малого","сделай первый шаг","разбей на части","просто начни",
           "выдели время","составь план"]
key = next(m.group(1) for m in
           (re.match(r"DEEPSEEK_API_KEY=(.+)", l.strip())
            for l in (ROOT/"backend/.env").read_text().splitlines()) if m)
TASKS = ["разобрать гараж и вывезти всё лишнее",
         "сделать презентацию по итогам квартала",
         "разобраться с налоговой декларацией"]
bad = 0
for task in TASKS:
    payload = {"model":"deepseek-v4-flash","temperature":0.3,"max_tokens":16384,
        "messages":[{"role":"system","content":SHRINK},
                    {"role":"user","content":f"Дело: {task}"}]}
    req = urllib.request.Request("https://api.deepseek.com/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type":"application/json","Authorization":f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=180) as r:
        step = json.load(r)["choices"][0]["message"]["content"].strip().strip('"«»')
    problems = []
    if any(g in step.lower() for g in GENERIC): problems.append("отговорка")
    if len(step) > len(task) + 20: problems.append("не уменьшил")
    if len(step) < 8: problems.append("пусто")
    bad += bool(problems)
    print(f"«{task}»\n  → {step}" + (f"\n  ✗ {', '.join(problems)}" if problems else ""))
print(f"\nбраком признано {bad} из {len(TASKS)}")
