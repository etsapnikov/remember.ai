package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Window
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Планировщик возвратов (PRD §4).
 *
 * Модель предлагает семантику («вечером»), время назначает код. Здесь нет ни одной
 * строки, зависящей от ответа LLM, — только окна пользователя и календарь.
 */
object Scheduler {

    /** Окна дня. Дефолты §4 — гипотеза, корректируется по фактическим переносам (§11). */
    data class Windows(
        val morning: LocalTime = LocalTime.of(8, 0),
        val day: LocalTime = LocalTime.of(12, 30),
        val evening: LocalTime = LocalTime.of(19, 30),
        val weekend: LocalTime = LocalTime.of(11, 0),
    ) {
        fun timeOf(window: Window): LocalTime = when (window) {
            Window.MORNING, Window.TOMORROW_MORNING -> morning
            Window.DAY -> day
            Window.EVENING -> evening
            Window.WEEKEND -> weekend
        }
    }

    /**
     * Вечернее послабление: записал в 21:50 «вечером» — вернём сегодня же, через
     * [EVENING_GRACE], но не позже [EVENING_CUTOFF]. Иначе ночная мысль ждёт сутки.
     */
    private val EVENING_GRACE: Duration = Duration.ofMinutes(10)
    private val EVENING_CUTOFF: LocalTime = LocalTime.of(22, 30)

    /**
     * Момент возврата для окна, посчитанный от времени записи.
     *
     * @param recordedAt момент нажатия кнопки, не момент разбора: между ними может
     *   лежать ночь оффлайна, и «завтра утром» должно остаться завтрашним для записи.
     */
    fun scheduleFor(
        window: Window,
        recordedAt: Instant,
        windows: Windows,
        zone: ZoneId,
        now: Instant = recordedAt,
    ): Instant {
        val recordedLocal = LocalDateTime.ofInstant(recordedAt, zone)
        val nowLocal = LocalDateTime.ofInstant(now, zone)
        val time = windows.timeOf(window)

        val target = when (window) {
            // Явное «завтра утром» — всегда следующий день после записи.
            Window.TOMORROW_MORNING -> recordedLocal.toLocalDate().plusDays(1).atTime(time)

            Window.WEEKEND -> nextWeekend(recordedLocal, time)

            Window.EVENING -> {
                val today = recordedLocal.toLocalDate().atTime(time)
                if (today.isAfter(recordedLocal)) {
                    today
                } else {
                    val soon = recordedLocal.plus(EVENING_GRACE)
                    if (soon.toLocalTime() <= EVENING_CUTOFF && soon.toLocalDate() == recordedLocal.toLocalDate()) {
                        soon
                    } else {
                        recordedLocal.toLocalDate().plusDays(1).atTime(time)
                    }
                }
            }

            // Окно уже прошло сегодня → ближайшее такое же завтра.
            Window.MORNING, Window.DAY -> {
                val today = recordedLocal.toLocalDate().atTime(time)
                if (today.isAfter(recordedLocal)) today
                else recordedLocal.toLocalDate().plusDays(1).atTime(time)
            }
        }

        // Разбор мог прийти из очереди спустя часы: назначенный момент, который уже
        // прошёл, превратился бы в мгновенный звонок посреди ночи.
        val instant = target.atZone(zone).toInstant()
        return if (instant.isAfter(now)) instant else nextWindowAfter(now, windows, zone)
    }

    /**
     * Следующее окно после [now] — для действия «позже».
     *
     * Пользователь не выбирает время: код сам ставит следующее окно и пишет об этом
     * в подтверждении (PRD §F-6).
     */
    fun nextWindowAfter(now: Instant, windows: Windows, zone: ZoneId): Instant {
        val local = LocalDateTime.ofInstant(now, zone)
        val today = local.toLocalDate()
        val candidates = listOf(windows.morning, windows.day, windows.evening)
            .map { today.atTime(it) }
            .filter { it.isAfter(local) }

        val target = candidates.minOrNull() ?: today.plusDays(1).atTime(windows.morning)
        return target.atZone(zone).toInstant()
    }

    /** Какое окно соответствует моменту — нужно, чтобы назвать причину возврата. */
    fun windowOf(instant: Instant, windows: Windows, zone: ZoneId): Window? {
        val time = LocalDateTime.ofInstant(instant, zone)
        val date = time.toLocalDate()
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            if (near(time.toLocalTime(), windows.weekend)) return Window.WEEKEND
        }
        return when {
            near(time.toLocalTime(), windows.morning) -> Window.MORNING
            near(time.toLocalTime(), windows.day) -> Window.DAY
            near(time.toLocalTime(), windows.evening) -> Window.EVENING
            else -> null
        }
    }

    private fun near(a: LocalTime, b: LocalTime): Boolean =
        Duration.between(a, b).abs() <= Duration.ofMinutes(30)

    /**
     * Ближайшие выходные. Суббота — основной день; если суббота уже прошла (или её
     * время миновало), берём воскресенье, и только потом — следующую субботу.
     */
    private fun nextWeekend(from: LocalDateTime, time: LocalTime): LocalDateTime {
        val date: LocalDate = from.toLocalDate()
        val candidates = buildList {
            var cursor = date
            repeat(9) {
                if (cursor.dayOfWeek == DayOfWeek.SATURDAY || cursor.dayOfWeek == DayOfWeek.SUNDAY) {
                    add(cursor.atTime(time))
                }
                cursor = cursor.plusDays(1)
            }
        }
        return candidates.firstOrNull { it.isAfter(from) } ?: date.plusDays(7).atTime(time)
    }
}
