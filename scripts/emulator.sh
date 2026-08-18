#!/usr/bin/env bash
#
# Эмулятор для отладки (Р-15/QA-2).
#
#   scripts/emulator.sh up        поднять и поставить сборку
#   scripts/emulator.sh install   переустановить сборку
#   scripts/emulator.sh seed      залить копию живой базы владельца
#   scripts/emulator.sh shot      снимок экрана
#   scripts/emulator.sh down      погасить
#
# Зачем. Отлаживать на телефоне дорого: он то спит, то теряет Wi-Fi-отладку, и
# каждая проверка требует владельца рядом. Класс багов, который нас кусал —
# жизненный цикл активити, гонки при закрытии экрана, — воспроизводится на
# эмуляторе за секунды и вообще не требует человека.
#
# Чего эмулятор НЕ заменяет, и путать нельзя:
#  - возвраты и фон: у Honor своя политика убийства процессов (QA-5);
#  - микрофон и распознавание: эмулятор отдаёт синтетический звук, GigaAM на нём
#    проверять бессмысленно;
#  - производительность ленты: цифры будут не те (QA-9).
# Всё это остаётся за телефоном. На маке ищем баги, на телефоне подтверждаем.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

AVD=zapomin
PORT=5560
SERIAL="emulator-$PORT"
PKG=ai.prinim.prinyal.debug
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

say() { printf '%s\n' "$*" >&2; }

booted() {
  [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

up() {
  if booted; then say "эмулятор уже поднят"; return 0; fi

  # Без окна: быстрее и не забирает фокус у владельца. Скриншоты снимаются
  # через adb и от окна не зависят.
  nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" \
    -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -port "$PORT" \
    > /tmp/emulator-$AVD.log 2>&1 &

  say "жду загрузки…"
  local i
  for i in $(seq 1 60); do
    booted && break
    sleep 5
  done
  booted || { say "не загрузился, лог: /tmp/emulator-$AVD.log"; return 1; }
  say "поднят: $SERIAL"
}

install() {
  [ -f "$APK" ] || { say "нет APK — собери: android/gradlew :app:assembleDebug"; return 1; }
  adb -s "$SERIAL" install -r -t "$APK" 2>&1 | tail -1 >&2
  # Разрешения выдаём сразу: диалоги в отладке только мешают.
  adb -s "$SERIAL" shell pm grant $PKG android.permission.RECORD_AUDIO 2>/dev/null
  adb -s "$SERIAL" shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
}

# Копия живой базы на эмулятор: отладка идёт на настоящих данных владельца,
# но сам телефон при этом в безопасности — трогаем копию, а не оригинал.
seed() {
  local backup
  backup="$(ls -dt "$ROOT"/backups/*/ 2>/dev/null | head -1)"
  [ -n "$backup" ] || { say "нет копии — сними: scripts/backup.sh"; return 1; }

  local work; work="$(mktemp -d)"
  tar xf "$backup/databases.tar" -C "$work" || return 1

  adb -s "$SERIAL" shell am force-stop $PKG
  local file
  for file in "$work"/prinyal.db*; do
    [ -f "$file" ] || continue
    adb -s "$SERIAL" push "$file" "/data/local/tmp/$(basename "$file")" >/dev/null
    adb -s "$SERIAL" shell "run-as $PKG cp /data/local/tmp/$(basename "$file") databases/"
    adb -s "$SERIAL" shell rm "/data/local/tmp/$(basename "$file")"
  done
  rm -rf "$work"
  say "залита копия от $(basename "$backup")"
}

shot() {
  local out="${1:-/tmp/emulator-shot.png}"
  adb -s "$SERIAL" exec-out screencap -p > "$out" 2>/dev/null
  say "$out"
}

case "${1:-up}" in
  up)      up && install ;;
  install) install ;;
  seed)    seed ;;
  shot)    shot "${2:-}" ;;
  down)    adb -s "$SERIAL" emu kill >/dev/null 2>&1; say "погашен" ;;
  *)       say "up | install | seed | shot | down"; exit 1 ;;
esac
