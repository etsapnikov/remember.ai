#!/usr/bin/env python3
"""
Замер дробления записей на пункты (1.6): старый промпт против нового на
живых записях владельца.

  scripts/eval_split.py path/to/prinyal.db [--limit N]

Берёт транскрипты, которые старый разбор порезал на ≥3 пункта, плюс те, где
владелец сам назвал жанр словом («идея», «напомни», «список», «купить»).
Каждый гоняет через SYSTEM v11 (git show release/1.5) и текущий v12, печатает
вид записи и число пунктов рядом. Цена ~$0.001 за вызов.
"""
import http.client, json, pathlib, re, sqlite3, subprocess, sys, time, urllib.error, urllib.request
from datetime import datetime

ROOT = pathlib.Path(__file__).resolve().parent.parent
KEY = next(m.group(1) for l in (ROOT / "backend/.env").read_text().splitlines()
           if (m := re.match(r"DEEPSEEK_API_KEY=(.+)", l.strip())))

def prompt_from(kotlin: str) -> str:
    m = re.search(r'val SYSTEM = """(.*?)""".trimIndent\(\)', kotlin, re.S)
    body = m.group(1)
    lines = body.split("\n")
    pad = min(len(l) - len(l.lstrip()) for l in lines if l.strip())
    return "\n".join(l[pad:] if len(l) >= pad else l for l in lines).strip()

NEW = prompt_from((ROOT / "android/app/src/main/java/ai/prinim/prinyal/llm/Prompt.kt").read_text())
OLD = prompt_from(subprocess.check_output(
    ["git", "show", "release/1.5:android/app/src/main/java/ai/prinim/prinyal/llm/Prompt.kt"],
    cwd=ROOT, text=True))

def ask(system: str, transcript: str):
    now = datetime.now()
    user = (f"Сейчас {now:%Y-%m-%d %H:%M}, будний день.\n"
            "Существующие разделы: Работа, Приложение, Отдых, Дом, Здоровье.\n\n"
            f"Текст: {transcript}")
    payload = {
        "model": "deepseek-v4-flash",
        "response_format": {"type": "json_object"},
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "temperature": 0.1, "max_tokens": 1500, "stream": False,
        "thinking": {"type": "disabled"},
    }
    req = urllib.request.Request("https://api.deepseek.com/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"})
    # Сеть до CloudFront-фронта DeepSeek с этого Mac моргает: один вызов из
    # трёх висит до таймаута. Повторы с паузой, а не падение всего замера.
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                body = json.load(r)
        except (urllib.error.URLError, TimeoutError, OSError, http.client.HTTPException, json.JSONDecodeError) as e:
            print(f"    [сеть] попытка {attempt + 1}: {type(e).__name__}", file=sys.stderr)
            time.sleep(2 * (attempt + 1))
            continue
        content = body["choices"][0]["message"]["content"].strip()
        if content:
            try:
                return json.loads(content)
            except json.JSONDecodeError:
                pass
        time.sleep(1)
    return None

def main():
    db = sqlite3.connect(sys.argv[1])
    limit = int(sys.argv[sys.argv.index("--limit") + 1]) if "--limit" in sys.argv else 40
    rows = db.execute("""
        select n.id, n.transcript, (select count(*) from items i where i.note_id = n.id) as n_items
        from notes n where n.deleted_at is null and n.transcript is not null and length(n.transcript) > 20
        order by n.created_at desc""").fetchall()
    marker = re.compile(r"\b(иде[яйю]|напомни|список|купить|нужно купить|не забыть)\b", re.I)
    picked = [r for r in rows if r[2] >= 3 or marker.search(r[1] or "")][:limit]

    out = []
    print(f"{'id':8} {'было':>4} {'v11':>4} {'v12':>4}  kind11→kind12  транскрипт")
    for nid, text, had in picked:
        a = ask(OLD, text); b = ask(NEW, text)
        if a is None or b is None:
            print(f"{nid[:8]}  — сеть не ответила, пропуск —  {text[:60]}")
            continue
        na, nb = len(a.get("items", [])), len(b.get("items", []))
        ka, kb = a.get("note_kind"), b.get("note_kind")
        out.append({"id": nid, "had": had, "v11": na, "v12": nb, "kind11": ka, "kind12": kb,
                    "items12": [i.get("text") for i in b.get("items", [])], "text": text})
        flag = " ←" if nb < na else ("  " if nb == na else " ↑")
        print(f"{nid[:8]} {had:>4} {na:>4} {nb:>4}{flag} {ka}→{kb}  {text[:70]}")
        (ROOT / "docs/eval-split.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))
    tot_a = sum(o["v11"] for o in out); tot_b = sum(o["v12"] for o in out)
    print(f"\nзаписей: {len(out)}   пунктов v11: {tot_a}   v12: {tot_b}   "
          f"среднее {tot_a/len(out):.2f} → {tot_b/len(out):.2f}")
    (ROOT / "docs/eval-split.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))

if __name__ == "__main__":
    main()
