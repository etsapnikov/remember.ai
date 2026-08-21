package ai.prinim.prinyal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Повторяющееся напоминание (Р-16.2): «напоминай каждый понедельник»,
 * «напоминай каждый месяц такого-то числа».
 *
 * Три формы, и больше не нужно. Владелец назвал две, третья — ежедневная —
 * стоит одну строку и закрывает «каждое утро». Всего остального (каждый второй
 * четверг, по будням, раз в квартал) в речи не звучало, а календарь, который
 * умеет всё, — это уже календарь, а не продукт про «сказал и забыл».
 *
 * Правило хранится строкой: `daily`, `weekly:mon`, `monthly:14`.
 */
sealed interface Repeat {

    object Daily : Repeat

    data class Weekly(val day: DayOfWeek) : Repeat

    /**
     * @param day число месяца, 1..31. Тридцать первое в коротком месяце
     *   съезжает на последний день, а не пропускается: человек, сказавший
     *   «каждое тридцать первое», в феврале ждёт напоминания, а не тишины.
     */
    data class Monthly(val day: Int) : Repeat

    fun wire(): String = when (this) {
        Daily -> "daily"
        is Weekly -> "weekly:" + DAYS.entries.first { it.value == day }.key
        is Monthly -> "monthly:$day"
    }

    /** Как это читается человеку: «каждый понедельник», «14 числа каждого месяца». */
    fun human(): String = when (this) {
        Daily -> "каждый день"
        is Weekly -> "каждый " + NAMES.getValue(day)
        is Monthly -> "$day числа каждого месяца"
    }

    /**
     * Ближайшее срабатывание строго после [after], в названное время суток.
     *
     * «Строго» — это инвариант: правило существует ровно затем, чтобы поставить
     * следующий возврат сразу после сработавшего, и момент, равный текущему,
     * дал бы бесконечный цикл в ту же миллисекунду.
     */
    fun next(after: LocalDateTime, at: LocalTime): LocalDateTime {
        val target = when (this) {
            Daily -> after.toLocalDate().atTime(at).let {
                if (it.isAfter(after)) it else it.plusDays(1)
            }

            is Weekly -> {
                var date = after.toLocalDate()
                // Сегодняшний нужный день годится, только если время ещё не прошло.
                if (date.dayOfWeek != day || !date.atTime(at).isAfter(after)) {
                    do { date = date.plusDays(1) } while (date.dayOfWeek != day)
                }
                date.atTime(at)
            }

            is Monthly -> {
                val first = onDay(after.toLocalDate(), day).atTime(at)
                if (first.isAfter(after)) first
                else onDay(after.toLocalDate().plusMonths(1), day).atTime(at)
            }
        }
        return target
    }

    companion object {
        private val DAYS = mapOf(
            "mon" to DayOfWeek.MONDAY,
            "tue" to DayOfWeek.TUESDAY,
            "wed" to DayOfWeek.WEDNESDAY,
            "thu" to DayOfWeek.THURSDAY,
            "fri" to DayOfWeek.FRIDAY,
            "sat" to DayOfWeek.SATURDAY,
            "sun" to DayOfWeek.SUNDAY,
        )

        private val NAMES = mapOf(
            DayOfWeek.MONDAY to "понедельник",
            DayOfWeek.TUESDAY to "вторник",
            DayOfWeek.WEDNESDAY to "среду",
            DayOfWeek.THURSDAY to "четверг",
            DayOfWeek.FRIDAY to "пятницу",
            DayOfWeek.SATURDAY to "субботу",
            DayOfWeek.SUNDAY to "воскресенье",
        )

        /** Разбор строки правила. Мусор — это null, а не исключение. */
        fun of(wire: String?): Repeat? {
            val raw = wire?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty()) return null
            if (raw == "daily") return Daily
            val (kind, arg) = raw.split(':', limit = 2).let {
                if (it.size == 2) it[0] to it[1] else return null
            }
            return when (kind) {
                "weekly" -> DAYS[arg]?.let(::Weekly)
                "monthly" -> arg.toIntOrNull()?.takeIf { it in 1..31 }?.let(::Monthly)
                else -> null
            }
        }

        private fun onDay(month: LocalDate, day: Int): LocalDate =
            month.withDayOfMonth(minOf(day, month.lengthOfMonth()))
    }
}
