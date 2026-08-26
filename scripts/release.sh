#!/usr/bin/env bash
#
# Релизный прогон кор-фич «Запомин.ai».
#
#   scripts/release.sh              ярусы 1–2: код и доставка, без сети
#   scripts/release.sh --live       плюс ярус 3: живые эвалы (стоит денег)
#   scripts/release.sh --device     плюс распознавание на телефоне
#   scripts/release.sh --all        всё сразу
#
# Зачем отдельный скрипт, когда есть `gradlew test`. Затем, что перед релизом
# вопрос звучит не «зелены ли тесты», а «работают ли четыре вещи, ради которых
# продукт существует». Прогон отвечает именно на этот вопрос и называет фичу, а
# не класс: упало — понятно, что именно перестало работать для человека.
#
# Ярусы. Первый — решения на записанной плёнке: быстро, офлайн, ничего не
# стоит. Второй — доставка: воркеры, уведомления, расписание; тоже офлайн, но
# проверяет то, что человек видит, а не то, что мы решили. Третий — живая
# модель: медленно и за деньги, поэтому по требованию.
#
# Карта «фича → чем проверяется» лежит в docs/release-checks.md.

set -uo pipefail
export LC_ALL="${LC_ALL:-ru_RU.UTF-8}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID="$ROOT/android"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

live=0; device=0
for arg in "$@"; do
    case "$arg" in
        --live) live=1 ;;
        --device) device=1 ;;
        --all) live=1; device=1 ;;
        *) echo "не знаю ключ $arg" >&2; exit 2 ;;
    esac
done

say() { printf '%s\n' "$*"; }
failed=()

# Прогон одной группы. Имя — фича, а не класс: в отчёте должно быть видно, что
# сломалось для человека, без похода в исходники.
# Ширину считаем в символах, а не байтах: printf '%-46s' меряет байты, и
# кириллица уезжает ровно вдвое.
pad() {
    local n=$(( $1 - ${#2} ))
    (( n > 0 )) && printf '%*s' "$n" ''
}

check() {
    local title="$1"; shift
    printf '  %s%s ' "$title" "$(pad 44 "$title")"
    if "$@" >"$ROOT/.release-log" 2>&1; then
        say "ок"
    else
        say "УПАЛО"
        failed+=("$title")
        sed -n '/FAILED\|AssertionError\|не дождались/p' "$ROOT/.release-log" | head -5 | sed 's/^/      /'
    fi
}

gradle_tests() {
    (cd "$ANDROID" && ./gradlew :app:testDebugUnitTest --tests "$1" -q)
}

say ""
say "Ярус 1 — решения (плёнка, офлайн)"
check "разбор комка на пункты"            gradle_tests '*CoreLoopFixturesTest*'
check "сроки и окна возвратов"            gradle_tests '*ReturnPolicyTest*'
check "точные даты"                       gradle_tests '*ExactDateTest*'
check "повторы"                           gradle_tests '*RepeatTest*'
check "раздел по записи"                  gradle_tests '*TopicAssignTest*'
check "связи между записями"              gradle_tests '*LinkCandidatesTest*'
check "«Покрутить идею» — правила"         gradle_tests '*SpinTest*'
check "люди: имена и склейка"             gradle_tests '*PersonIdentityTest*'
check "впечатление дня"                   gradle_tests '*DayLineTest*'
check "итог недели"                       gradle_tests '*WeeklySummaryTest*'

say ""
say "Ярус 2 — доставка (то, что видит человек)"
check "запись → лента → возврат"          gradle_tests '*CaptureToFeedTest*'
check "напоминание пришло и отвечено"     gradle_tests '*ReturnDeliveryTest*'
check "вечерний вопрос о дне"             gradle_tests '*DayReflectionTest*'
check "люди и «Покрутить идею»"           gradle_tests '*PeopleAndSpinTest*'

say ""
say "Ярус 2 — остальной код"
check "всё прочее"                        gradle_tests '*'

if [[ $device -eq 1 ]]; then
    say ""
    say "Ярус 2 — телефон"
    if adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {found=1} END {exit !found}'; then
        say "  ВНИМАНИЕ: прогон на телефоне сносит приложение вместе с данными."
        say "  Сначала: scripts/backup.sh"
        check "распознавание на arm64"     bash -c "cd '$ANDROID' && ./gradlew :app:connectedDebugAndroidTest -q"
    else
        say "  телефон не подключён — пропущено"
    fi
fi

if [[ $live -eq 1 ]]; then
    say ""
    say "Ярус 3 — живая модель (деньги и время)"
    check "английский в русской речи"      python3 "$ROOT/scripts/eval_english.py"
    check "вопросы «Покрутить идею»"       python3 "$ROOT/scripts/eval_interview.py"
    check "нужен ли ризонинг"              python3 "$ROOT/scripts/eval_reasoning.py"
fi

rm -f "$ROOT/.release-log"
say ""
if [[ ${#failed[@]} -eq 0 ]]; then
    say "Всё зелёное. Можно собирать релиз."
    exit 0
fi
say "Не работает: ${#failed[@]}"
for f in "${failed[@]}"; do say "  • $f"; done
say ""
say "Релиз не собираем, пока это красное."
exit 1
