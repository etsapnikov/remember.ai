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
OUT="${OUT:-$ROOT/docs/review-1_0_2}"

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

cleanup_junk() {
  adb -s "$SERIAL" shell "run-as $PKG sqlite3 databases/prinyal.db \
    \"DELETE FROM notes WHERE transcript IS NULL AND duration_ms < 5000\"" 2>/dev/null
}
trap cleanup_junk EXIT

# Лента открывается свайпом вверх с экрана записи (capture-first).
to_feed() { restart; swipe 540 2000 540 800 250 3; cleanup_junk; sleep 2; }

surfaces() {
  local suffix="$1"

  restart
  shot "01-запись$suffix"

  # Микрофон эмулятора даёт тишину, и авто-стоп исправно сохраняет пустышку
  # «не расслышал». Раньше она портила съёмку — карточка ошибки оказывалась
  # первой в ленте вместо нужной записи. Теперь она же и снимается: другого
  # способа показать этот экран нет, а показать его надо.
  # Пустышку снимаем до уборки: to_feed теперь чистит ленту сам.
  restart
  swipe 540 2000 540 800 250 3
  tap 540 620 3
  shot "15-ошибка$suffix"
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK; sleep 2

  to_feed
  shot "02-лента$suffix"

  # Карточка записи: первая заметка списка.
  tap 540 620 3
  shot "03-карточка$suffix"

  # Раскрытие пункта — тап по тексту пункта.
  tap 540 1560 3
  shot "04-раскрытие-пункта$suffix"

  # Правка из раскрытия. Координата выверена по снимку: промах здесь даёт
  # дубль предыдущего кадра, а не пустоту, — и это незаметно глазом.
  tap 330 1796 3
  shot "05-правка-пункта$suffix"
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK; sleep 2

  # Карточка идеи: «Собрано», свёрнутый сырец, «Связано», «Покрутить» — всё
  # новое в 1.0.2 живёт на одной карточке (Р-15.11, Р-15.13, Р-15.14).
  # Низ карточки идеи: свёрнутый сырец, «Связано», «Покрутить» — всё новое в
  # 1.0.2 живёт ниже пунктов (Р-15.11, Р-15.14).
  to_feed
  tap 540 620 3
  swipe 540 1900 540 500 250 2
  shot "12-идея-низ-карточки$suffix"
  swipe 540 1900 540 500 250 2
  shot "13-идея-связи-и-покрутить$suffix"

  to_feed
  tap 380 190 3
  shot "06-разделы$suffix"
  # «Решения» — первая строка списка разделов (Р-15.10).
  tap 540 375 3
  shot "14-решения$suffix"
  adb -s "$SERIAL" shell input keyevent KEYCODE_BACK; sleep 2
  tap 540 515 3
  shot "07-внутри-раздела$suffix"

  to_feed
  tap 640 190 3
  shot "08-неделя$suffix"

  to_feed
  tap 1000 190 3
  shot "09-настройки-верх$suffix"
  swipe 540 1800 540 700 250 2
  shot "10-настройки-низ$suffix"
  swipe 540 2000 540 400 250 2
  swipe 540 2000 540 400 250 2
  swipe 540 2000 540 400 250 2
  tap 540 1900 3
  shot "11-настройки-разработчик$suffix"
}

mkdir -p "$OUT"
mode="${1:-both}"

# Каждый прогон начинается с экрана записи, а микрофон эмулятора даёт тишину:
# авто-стоп исправно сохраняет пустышку «не расслышал». За несколько прогонов
# верх ленты забивается фантомами, и снимать становится нечего. Поэтому съёмка
# сама за собой убирает: копию базы всё равно перезальёт `emulator.sh seed`.
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
