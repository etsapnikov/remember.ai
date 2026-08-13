#!/usr/bin/env bash
#
# Сборка и доставка «Запомин.ai» на телефон без ручной возни с портами.
#
#   scripts/deploy.sh            собрать и поставить
#   scripts/deploy.sh --no-build поставить уже собранный APK
#   scripts/deploy.sh --watch    следить за исходниками и ставить на каждое изменение
#   scripts/deploy.sh --find     только найти телефон и пришпилить порт
#
# Зачем: адрес и порт отладки у телефона плавают — Wi-Fi-отладка выдаёт случайный
# порт при каждом включении, а DHCP меняет адрес. Скрипт ищет телефон сам:
# уже подключён → адрес из кэша → mDNS → скан подсети. Найдя, переводит adbd на
# постоянный порт 5555, чтобы следующий запуск был мгновенным.
#
# После перезагрузки телефона порт снова станет случайным: разбудите телефон,
# включите «Отладку по Wi-Fi» и запустите скрипт — он найдёт устройство сканом и
# снова пришпилит 5555.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID="$ROOT/android"
APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"
CACHE="$ROOT/.deploy-target"
PKG="ai.prinim.prinyal.debug"
LAUNCH="$PKG/ai.prinim.prinyal.capture.CaptureActivity"
PINNED_PORT=5555

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

say() { printf '%s\n' "$*" >&2; }

# --- поиск телефона ---------------------------------------------------------

connected() {
  adb devices | awk 'NR>1 && $2=="device" {print $1; exit}'
}

try_connect() {
  local target="$1"
  adb connect "$target" >/dev/null 2>&1
  sleep 1
  adb devices | awk -v t="$target" 'NR>1 && $1==t && $2=="device" {found=1} END {exit !found}'
}

# adb умеет находить спаренные устройства сам, если включена Wi-Fi-отладка.
find_via_mdns() {
  ADB_MDNS_OPENSCREEN=1 adb mdns services 2>/dev/null \
    | awk '/_adb-tls-connect/ {print $NF}' | head -3
}

# Живые хосты подсети: сначала arp-кэш (мгновенно), затем ping-развёртка.
subnet_hosts() {
  local self prefix
  self="$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null)"
  [ -z "$self" ] && return 0
  prefix="${self%.*}"

  for i in $(seq 1 254); do
    ping -c 1 -W 200 "$prefix.$i" >/dev/null 2>&1 &
  done
  wait
  arp -a | awk -v p="$prefix." '$2 ~ /\(/ {gsub(/[()]/,"",$2); if (index($2,p)==1) print $2}'
}

port_open() {
  nc -z -G 1 -w 1 "$1" "$2" >/dev/null 2>&1
}

find_phone() {
  local target host port

  # 1. Уже подключён.
  target="$(connected)"
  if [ -n "$target" ]; then echo "$target"; return 0; fi

  # 2. Адрес из прошлого запуска.
  if [ -f "$CACHE" ]; then
    target="$(cat "$CACHE")"
    if try_connect "$target"; then
      say "нашёл по кэшу: $target"
      echo "$target"; return 0
    fi
  fi

  # 3. mDNS — работает, когда телефон объявляет себя.
  for target in $(find_via_mdns); do
    if try_connect "$target"; then
      say "нашёл через mDNS: $target"
      echo "$target"; return 0
    fi
  done

  # 4. Скан подсети: сперва пришпиленный порт на всех живых хостах.
  say "сканирую подсеть…"
  local hosts; hosts="$(subnet_hosts)"
  for host in $hosts; do
    if port_open "$host" "$PINNED_PORT" && try_connect "$host:$PINNED_PORT"; then
      say "нашёл на пришпиленном порту: $host:$PINNED_PORT"
      echo "$host:$PINNED_PORT"; return 0
    fi
  done

  # 5. Порт Wi-Fi-отладки случайный — ищем его перебором на живых хостах.
  say "пришпиленного порта нет, ищу порт Wi-Fi-отладки…"
  for host in $hosts; do
    for port in $(seq 30000 50000); do
      port_open "$host" "$port" && {
        if try_connect "$host:$port"; then
          say "нашёл: $host:$port"
          echo "$host:$port"; return 0
        fi
      } &
      (( port % 800 == 0 )) && wait
    done
    wait
  done

  return 1
}

# Постоянный порт: следующий запуск найдёт телефон мгновенно, без перебора.
pin_port() {
  local target="$1" host="${1%%:*}"
  [ "${target##*:}" = "$PINNED_PORT" ] && { echo "$target"; return 0; }

  adb -s "$target" tcpip "$PINNED_PORT" >/dev/null 2>&1 || { echo "$target"; return 0; }
  sleep 2
  if try_connect "$host:$PINNED_PORT"; then
    say "порт пришпилен: $host:$PINNED_PORT"
    echo "$host:$PINNED_PORT"
  else
    echo "$target"
  fi
}

# --- сборка и установка -----------------------------------------------------

build() {
  say "собираю…"
  ( cd "$ANDROID" && ./gradlew :app:assembleDebug --console=plain -q ) || {
    say "СБОРКА УПАЛА"; return 1
  }
}

update_time() {
  adb -s "$1" shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/lastUpdateTime/ {print $2; exit}'
}

# Право ставить пакеты выдаём через adb, чтобы не гонять владельца в настройки.
grant_installer() {
  adb -s "$1" shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
}

# Проверки, которые останавливают установку и ждут тапа. Ставим каждый раз:
# обновление прошивки их возвращает, и тогда установка молча зависает в
# ожидании подтверждения, которого никто не сделает.
#
# Главный тумблер здесь недоступен: «Monitor apps installed by ADB» в разделе
# «Для разработчиков» ключа в `settings` не имеет и снимается только руками —
# без этого прошивка требует Continue → Install → отпечаток на каждую установку.
prepare_device() {
  local target="$1"
  adb -s "$target" shell settings put global verifier_verify_adb_installs 0 >/dev/null 2>&1
  adb -s "$target" shell settings put global package_verifier_enable 0 >/dev/null 2>&1
  adb -s "$target" shell settings put secure install_non_market_apps 1 >/dev/null 2>&1
}

# Обновление силами самого приложения: кладём APK в его каталог и говорим «ставь».
# Диалога подтверждения при этом нет — приложение обновляет само себя (Android 12+).
self_update() {
  local target="$1" before="$2"
  local dir="/sdcard/Android/data/$PKG/files"

  # Каталог создаётся приложением при первом обращении — до него он может не
  # существовать; shell вправе его завести сам.
  adb -s "$target" shell mkdir -p "$dir" >/dev/null 2>&1
  adb -s "$target" shell "[ -d '$dir' ]" 2>/dev/null || return 1

  adb -s "$target" shell rm -f "$dir/update-status.txt" >/dev/null 2>&1
  adb -s "$target" push "$APK" "$dir/update.apk" >/dev/null 2>&1 || return 1

  # Прошивка не поднимает процесс ради бродкаста — даже с
  # FLAG_INCLUDE_STOPPED_PACKAGES приёмник молчит, пока приложение не живо.
  # Поэтому сначала будим, потом командуем. Приложение всё равно перезапустится
  # после обновления, так что лишним этот запуск не будет.
  adb -s "$target" shell am start -n "$LAUNCH" >/dev/null 2>&1
  local w
  for w in 1 2 3 4 5 6 7 8 9 10; do
    adb -s "$target" shell pidof "$PKG" 2>/dev/null | grep -q '[0-9]' && break
    sleep 1
  done

  # -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES.
  adb -s "$target" shell am broadcast -f 0x20 \
    -a ai.prinim.prinyal.action.SELF_UPDATE \
    -n "$PKG/ai.prinim.prinyal.update.UpdateReceiver" >/dev/null 2>&1 || return 1

  local i status
  for i in $(seq 1 60); do
    sleep 2
    [ "$(update_time "$target")" != "$before" ] && {
      adb -s "$target" shell rm -f "$dir/update.apk" >/dev/null 2>&1
      return 0
    }
    status="$(adb -s "$target" shell cat "$dir/update-status.txt" 2>/dev/null | tr -d '\r')"
    case "$status" in
      rejected*|failed*|pending_user_action)
        say "самообновление не прошло: $status"
        return 1
        ;;
    esac
  done
  say "самообновление не ответило за две минуты"
  return 1
}

install_apk() {
  local target="$1"
  [ -f "$APK" ] || { say "нет APK: $APK"; return 1; }

  local before after
  before="$(update_time "$target")"
  grant_installer "$target"
  prepare_device "$target"

  # Самообновление силами приложения — только по явной просьбе: MagicOS на
  # Android 16 отвечает на USER_ACTION_NOT_REQUIRED отказом даже когда
  # установщиком записано само приложение. Проверено на устройстве, не догадка.
  # На другой прошивке ветка заработает без правок.
  if [ "${SELF_UPDATE:-0}" = "1" ] && self_update "$target" "$before"; then
    say "установлено самим приложением: $(update_time "$target")"
    return 0
  fi

  # Обычный путь. Молчит он не сам по себе, а потому что в «Для разработчиков»
  # снят «Monitor apps installed by ADB» — без этого прошивка требует
  # подтверждения с отпечатком на каждую установку.
  #
  # `-i` записывает установщиком само приложение: это ничего не стоит и
  # оставляет ветку самообновления рабочей на будущее.
  adb -s "$target" install -r -i "$PKG" "$APK" 2>&1 | tail -1 >&2 || return 1

  after="$(update_time "$target")"
  if [ "$before" = "$after" ]; then
    say "ВНИМАНИЕ: время установки не изменилось — на телефоне могла остаться старая сборка"
    return 1
  fi
  say "установлено: $after"
}

relaunch() {
  local target="$1"
  adb -s "$target" shell am force-stop "$PKG" >/dev/null 2>&1
  adb -s "$target" shell am start -n "$LAUNCH" >/dev/null 2>&1
}

deploy() {
  local target
  target="$(find_phone)" || {
    say "телефон не найден. Разбудите его, проверьте Wi-Fi и «Отладку по Wi-Fi»."
    return 1
  }
  echo "$target" > "$CACHE"
  target="$(pin_port "$target")"
  echo "$target" > "$CACHE"

  [ "${SKIP_BUILD:-0}" = "1" ] || build || return 1
  install_apk "$target" || return 1
  [ "${NO_LAUNCH:-0}" = "1" ] || relaunch "$target"
}

# --- режимы -----------------------------------------------------------------

case "${1:-}" in
  --find)
    target="$(find_phone)" && { echo "$target" > "$CACHE"; pin_port "$target" > "$CACHE"; cat "$CACHE"; }
    ;;
  --no-build)
    SKIP_BUILD=1 deploy
    ;;
  --watch)
    say "слежу за исходниками, Ctrl+C чтобы прекратить"
    deploy
    last="$(find "$ANDROID/app/src" "$ROOT/assets" -type f -newer /dev/null 2>/dev/null | xargs stat -f '%m' 2>/dev/null | sort -rn | head -1)"
    while true; do
      sleep 3
      now="$(find "$ANDROID/app/src" "$ROOT/assets" -type f 2>/dev/null | xargs stat -f '%m' 2>/dev/null | sort -rn | head -1)"
      if [ "$now" != "$last" ]; then
        last="$now"
        say "— изменения, пересобираю —"
        deploy
      fi
    done
    ;;
  *)
    deploy
    ;;
esac
