#!/usr/bin/env python3
"""Ярус 2: английские слова в русской речи (запрос владельца от 25.08).

Владелец говорит по-русски, но половина его работы — англоязычная: harness,
job description, GCR, markdown, DeepSeek. Модель распознавания у нас
**одноязычная**: GigaAM-RNNT обучена на русском и латиницу выдать физически не
может. Она делает единственное, что умеет, — записывает английское слово
кириллицей на слух: «джоп дискрипшен», «дета сета», «мэджик девять про макс».

Отсюда вопрос не «как научить распознавание английскому» (никак, не та
модель), а «где чинить после него». Меряем три ответа на одном наборе:

    ничего       — как сейчас
    словарь      — ручные автозамены, которые владелец заводит сам
    нормализация — отдельная просьба к модели вернуть латиницу

Кейсы — не выдуманные. Каждый взят из живых заметок владельца (102 записи,
3102 слова): фраза приведена так, как её услышало распознавание.

    python3 scripts/eval_english.py

Контрольные кейсы обязательны. Починить английское легко ценой того, что
чинилка начнёт переписывать русскую речь, — а это хуже исходной болезни: там
владелец видел кривое слово, здесь увидит чужие слова вместо своих.
"""
import json, pathlib, re, sys, time, urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
KEY = next(m.group(1) for m in
           (re.match(r"DEEPSEEK_API_KEY=(.+)", l.strip())
            for l in (ROOT / "backend/.env").read_text().splitlines()) if m)

# Что услышало распознавание → какие термины обязаны появиться латиницей.
#
# `want` — то, что должно найтись в ответе (регистр не важен). `keep` — слова,
# которые обязаны уцелеть: ими ловим чинилку, которая увлеклась.
CASES = [
    dict(heard="из идей нужно разобраться будет ли в аппе дипсика работать веб поиск",
         want=["deepseek"], keep=["идей", "веб поиск"]),
    dict(heard="про активный харнес также должен подсказывать какое время оптимально",
         want=["harness"], keep=["время", "оптимально"]),
    dict(heard="еще надо на интерфейсе поддержать вот в этом формате аля ноушн возможность топик для заметки выбирать",
         want=["notion"], keep=["интерфейсе", "заметки"]),
    dict(heard="также надо бы маркдаун поддержать строго говоря потому что когда заметка парситься",
         want=["markdown"], keep=["поддержать", "заметка"]),
    dict(heard="напомни мне двадцать восьмого сентября что состоится презентация онор на которой покажут новые мэджик девять про макс",
         want=["honor", "magic"], keep=["двадцать восьмого сентября", "презентация"]),
    dict(heard="нужно сегодня запустить работы по сбору дета сета для мемри юзедж",
         want=["dataset", "memory usage"], keep=["сегодня", "запустить"]),
    dict(heard="построение системы аналитики стартап стайл построение своей вал платформы",
         want=["style"], keep=["аналитики", "платформы"]),
    dict(heard="нужно актуализировать джоп дискрипшн для хд сделать акцент на работе с данными",
         want=["job description"], keep=["акцент", "с данными"]),
    dict(heard="нужно написать гайдлайн как прийти от результатов сбс к доменам критерием джисиар для скиллов",
         want=["sbs", "gcr"], keep=["гайдлайн", "доменам"]),
    dict(heard="тридцати секунд уходит на обработку заметки это очень долго дипсик флеш без ризанинга должен мгновенно отвечать",
         want=["deepseek", "flash", "reasoning"], keep=["тридцати секунд", "мгновенно"]),
    dict(heard="надо одебашить пингпонг идеями по заметке оно короче сломано появляется кнопка пингпонга",
         want=["ping"], keep=["сломано", "кнопка"]),
    dict(heard="нужно проверить как работает домайнинг фактов когда харнес проактивно запрашивает информацию про человека",
         want=["harness"], keep=["фактов", "человека"]),
    # Контрольные: английского нет, трогать нечего.
    dict(heard="напомни завтра позвонить маме и спросить как она себя чувствует после больницы",
         want=[], keep=["напомни завтра", "маме", "больницы"]),
    dict(heard="сегодня был тяжелый день много встреч устал но кажется сдвинулись с места по найму",
         want=[], keep=["тяжелый день", "встреч", "по найму"]),
    dict(heard="юля просила подобрать ей новое белье к выходным и не забыть про подарок сестре",
         want=[], keep=["юля", "белье", "подарок сестре"]),
    # Ловушка: русские слова, звучащие как англицизмы.
    dict(heard="надо валить все что мы делаем через проверку и не тянуть с этим до конца недели",
         want=[], keep=["валить", "проверку", "конца недели"]),
]

# Словарь владельца на сегодня: всё, что он завёл руками за месяц работы.
DICT = {"харнес": "harness", "лега": "LEGO", "хада": "хэда",
        "докитовкой": "додиктовкой", "хд": "head"}

NORMALIZE = """Ты правишь расшифровку русской речи. Человек говорит по-русски,
но рабочие термины произносит по-английски, а распознавание записывает их
кириллицей на слух: «джоп дискрипшен», «дета сета», «мэджик девять про макс».

Верни тот же текст, вернув латиницу только там, где человек явно произнёс
английское слово или название. Область — работа с продуктом, ИИ, наймом:
harness, job description, dataset, memory usage, markdown, reasoning, DeepSeek,
Notion, Honor Magic, GCR, SBS, ping-pong.

Английское слово в русском падеже — тоже английское слово. «дипсика»,
«дета сета», «мемри юзедж», «стартап стайл» произнесены по-английски, значит
идут латиницей в исходной форме: DeepSeek, dataset, memory usage, startup style.
Падеж пропадает — это правильно, латиница не склоняется.

Не трогай ничего больше. Русские слова, порядок слов, отсутствие знаков
препинания — оставь как есть. Если английского в тексте нет, верни его слово в
слово. Сомневаешься — не меняй.

Ответ — JSON: {"text": "..."}"""


def dictionary(text: str) -> str:
    for src, dst in sorted(DICT.items(), key=lambda kv: -len(kv[0])):
        text = re.sub(rf"(?<![\w]){re.escape(src)}(?![\w])", dst, text, flags=re.I)
    return text


def normalize(text: str) -> tuple[str, float]:
    payload = {
        "model": "deepseek-v4-flash", "max_tokens": 2048, "temperature": 0.0,
        "response_format": {"type": "json_object"},
        "thinking": {"type": "disabled"},
        "messages": [{"role": "system", "content": NORMALIZE},
                     {"role": "user", "content": text}],
    }
    req = urllib.request.Request(
        "https://api.deepseek.com/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {KEY}"})
    started = time.time()
    with urllib.request.urlopen(req, timeout=120) as r:
        body = json.load(r)
    took = time.time() - started
    return json.loads(body["choices"][0]["message"]["content"])["text"], took


def score(case, out):
    low = out.lower()
    fixed = [w for w in case["want"] if w.lower() in low]
    missed = [w for w in case["want"] if w.lower() not in low]
    broken = [k for k in case["keep"] if k.lower() not in low]
    return fixed, missed, broken


def run(name, fn):
    fixed = missed = broken = 0
    times, damage = [], []
    for case in CASES:
        started = time.time()
        out = fn(case["heard"])
        if isinstance(out, tuple):
            out, took = out
        else:
            took = time.time() - started
        f, m, b = score(case, out)
        fixed += len(f); missed += len(m); broken += len(b)
        times.append(took)
        if b:
            damage.append((case["heard"], out, b))
    want = sum(len(c["want"]) for c in CASES)
    keep = sum(len(c["keep"]) for c in CASES)
    print(f"\n{name}")
    print(f"  терминов починено {fixed}/{want}"
          f"   упущено {missed}"
          f"   русского испорчено {broken}/{keep}")
    print(f"  на фразу {sum(times)/len(times):.2f} с")
    for heard, out, b in damage:
        print(f"    ! пропало {b}")
        print(f"      было:  {heard[:90]}")
        print(f"      стало: {out[:90]}")
    return dict(name=name, fixed=fixed, want=want, missed=missed,
                broken=broken, keep=keep, seconds=sum(times) / len(times))


if __name__ == "__main__":
    print(f"кейсов {len(CASES)}, из них контрольных "
          f"{sum(1 for c in CASES if not c['want'])}")
    report = [run("ничего", lambda t: t),
              run("словарь владельца", dictionary),
              run("нормализация моделью", normalize)]
    (ROOT / "docs/eval-english.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1) + "\n")
    print("\nотчёт: docs/eval-english.json")
