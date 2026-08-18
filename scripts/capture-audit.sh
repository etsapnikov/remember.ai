#!/usr/bin/env bash
#
# Снимки всех поверхностей для дизайн-аудита (Д-7).
#
#   scripts/capture-audit.sh          снять в обеих темах
#   scripts/capture-audit.sh dark     только тёмная
#
# Скрипт, а не разовая съёмка: аудит будет повторяться каждую версию, и набор
# должен пересниматься одной командой. Иначе на второй раз половина состояний
# не воспроизведётся, и сравнивать будет не с чем.
#
# Состояния, которые случайно не попадаются — пустые экраны, разбор в процессе —
# готовятся здесь намеренно: ради них всё и затевалось.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

SERIAL="${SERIAL:-emulator-5560}"
PKG=ai.prinim.prinyal.debug
CAPTURE="$PKG/ai.prinim.prinyal.capture.CaptureActivity"
OUT="$ROOT/docs/audit-1_0_2"

say() { printf '%s\n' "$*" >&2; }
tap() { adb -s "$SERIAL" shell input tap "$1" "$2"; sleep "${3:-2}"; }
swipe() { adb -s "$SERIAL" shell input swipe "$1" "$2" "$3" "$4" "${5:-250}"; sleep "${6:-2}"; }

shot() {
  local name="$1"
  adb -s "$SERIAL" exec-out screencap -p > "$OUT/$name.png" 2>/dev/null
  say "  $name"
}

restart() {
  adb -s "$SERIAL" shell am force-stop $PKG
  adb -s "$SERIAL" shell am start -n "$CAPTURE" >/dev/null 2>&1
  sleep 3
}

# Лента открывается свайпом вверх с экрана записи (capture-first).
to_feed() { restart; swipe 540 2000 540 800 250 3; }

surfaces() {
  local suffix="$1"

  restart
  shot "01-запись$suffix"

  to_feed
  shot "02-лента$suffix"

  # Карточка записи: первая заметка списка.
  tap 540 620 3
  shot "03-карточка$suffix"

  # Раскрытие пункта — тап по тексту пункта.
  tap 540 1100 3
  shot "04-раскрытие-пункта$suffix"

  # Правка из раскрытия. Координата выверена по снимку: промах здесь даёт
  # дубль предыдущего кадра, а не пустоту, — и это незаметно глазом.
  tap 330 1796 3
  shot "05-правка-пункта$suffix"
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK; sleep 2

  to_feed
  tap 380 190 3
  shot "06-разделы$suffix"
  tap 540 330 3
  shot "07-внутри-раздела$suffix"

  to_feed
  tap 640 190 3
  shot "08-неделя$suffix"

  to_feed
  tap 1000 190 3
  shot "09-настройки-верх$suffix"
  swipe 540 1800 540 700 250 2
  shot "10-настройки-низ$suffix"
  swipe 540 1800 540 600 250 2
  shot "11-настройки-разработчик$suffix"
}

mkdir -p "$OUT"
mode="${1:-both}"

if [ "$mode" = "both" ] || [ "$mode" = "dark" ]; then
  say "тёмная тема:"
  adb -s "$SERIAL" shell cmd uimode night yes >/dev/null 2>&1
  sleep 3
  surfaces ""
fi

if [ "$mode" = "both" ] || [ "$mode" = "light" ]; then
  say "светлая тема:"
  adb -s "$SERIAL" shell cmd uimode night no >/dev/null 2>&1
  sleep 3
  surfaces "-светлая"
fi

say ""
say "снято в $OUT: $(ls "$OUT" | wc -l | tr -d ' ') файлов"
