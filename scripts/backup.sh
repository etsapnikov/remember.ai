#!/usr/bin/env bash
#
# Резервная копия живой базы владельца (QA-4).
#
#   scripts/backup.sh
#
# Копия обязана сниматься **до** каждого прогона с миграциями. База — не кэш,
# который можно пересобрать: это единственный экземпляр дневника, и миграции
# необратимы.
#
# Копируем все три файла SQLite, а не один: свежие записи живут в `-wal`, и
# копия без него отстаёт на всё, что человек наговорил после последней
# контрольной точки.
#
# Копия проверяется сразу же. Непроверенная копия — не копия, а надежда:
# пустой или битый файл обнаруживается ровно тогда, когда он нужен.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="ai.prinim.prinyal.debug"
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

say() { printf '%s\n' "$*" >&2; }

device="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
if [ -z "$device" ]; then
  say "телефон не найден: разбуди его и включи «Отладку по Wi-Fi»"
  exit 1
fi

target="$ROOT/backups/$(date +%Y-%m-%d-%H%M)"
mkdir -p "$target"

adb -s "$device" shell "run-as $PKG sh -c 'cd databases && tar cf - .'" > "$target/databases.tar" 2>/dev/null
adb -s "$device" shell "run-as $PKG cat files/analytics.jsonl" > "$target/analytics.jsonl" 2>/dev/null

if [ ! -s "$target/databases.tar" ]; then
  say "копия пустая — удаляю, чтобы она не притворялась копией"
  rm -rf "$target"
  exit 1
fi

# Проверка: база открывается, таблицы на месте, целостность в порядке.
work="$(mktemp -d)"
tar xf "$target/databases.tar" -C "$work"
python3 - "$work" "$target" <<'PY'
import pathlib, sqlite3, sys

work, target = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
db = work / "prinyal.db"
if not db.is_file():
    raise SystemExit("в архиве нет prinyal.db")

conn = sqlite3.connect(db)
conn.execute("pragma wal_checkpoint(FULL)")

check = conn.execute("pragma integrity_check").fetchone()[0]
if check != "ok":
    raise SystemExit(f"база не целая: {check}")

tables = ("notes", "items", "returns", "topics", "replacements", "note_segments", "entities")
lines = [f"схема: {conn.execute('pragma user_version').fetchone()[0]}"]
for name in tables:
    try:
        lines.append(f"{name}: {conn.execute(f'select count(*) from {name}').fetchone()[0]}")
    except sqlite3.Error:
        lines.append(f"{name}: нет таблицы")

notes = conn.execute("select count(*) from notes").fetchone()[0]
if notes == 0:
    raise SystemExit("в копии ноль заметок — это не похоже на живую базу")

(target / "checked.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(f"  {line}" for line in lines))
PY
status=$?
rm -rf "$work"

if [ $status -ne 0 ]; then
  say "копия не прошла проверку — оставляю её на месте для разбора: $target"
  exit 1
fi

say "копия снята и проверена: $target"
