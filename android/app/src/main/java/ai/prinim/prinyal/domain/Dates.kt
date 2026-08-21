package ai.prinim.prinyal.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Даты в интерфейсе — одним местом (аудит Д-7, косметика).
 *
 * Русская локаль печатает сокращение месяца с точкой: «18 авг.». Рядом с
 * точкой-разделителем это давало «18 авг. · 14:38» — в моноширинном наборе две
 * точки подряд читаются как мусор, а не как дата. Точку снимаем здесь, а не в
 * шести местах вызова: разошлись бы на первой же правке.
 */
object Dates {

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))
    private val DAY_FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale("ru"))

    fun day(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clean(Instant.ofEpochMilli(millis).atZone(zone).format(DAY))

    fun dayTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clean(Instant.ofEpochMilli(millis).atZone(zone).format(DAY_TIME))

    fun day(temporal: java.time.temporal.TemporalAccessor): String = clean(DAY.format(temporal))

    /** Только время: дата в ленте живёт в разделителе дня (Д-24). */
    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(HHMM)

    /** «19 августа» — для разделителя дня, где дата названа полностью. */
    fun dayFull(date: java.time.LocalDate): String = date.format(DAY_FULL)

    private val WEEKDAY = mapOf(
        java.time.DayOfWeek.MONDAY to "в понедельник",
        java.time.DayOfWeek.TUESDAY to "во вторник",
        java.time.DayOfWeek.WEDNESDAY to "в среду",
        java.time.DayOfWeek.THURSDAY to "в четверг",
        java.time.DayOfWeek.FRIDAY to "в пятницу",
        java.time.DayOfWeek.SATURDAY to "в субботу",
        java.time.DayOfWeek.SUNDAY to "в воскресенье",
    )

    /**
     * Когда это было, словами: «сегодня», «в понедельник», «18 авг».
     *
     * День недели живёт ровно неделю: «в понедельник» через месяц — это уже
     * загадка, а не ответ.
     */
    fun whenWas(
        millis: Long,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> "сегодня"
            days == 1L -> "вчера"
            days in 2..6 -> WEEKDAY.getValue(date.dayOfWeek)
            else -> day(millis, zone)
        }
    }

    private val WEEKDAY_TO = mapOf(
        java.time.DayOfWeek.MONDAY to "на понедельник",
        java.time.DayOfWeek.TUESDAY to "на вторник",
        java.time.DayOfWeek.WEDNESDAY to "на среду",
        java.time.DayOfWeek.THURSDAY to "на четверг",
        java.time.DayOfWeek.FRIDAY to "на пятницу",
        java.time.DayOfWeek.SATURDAY to "на субботу",
        java.time.DayOfWeek.SUNDAY to "на воскресенье",
    )

    /**
     * Когда это будет: «на сегодня», «на завтра», «на понедельник», «на 24 авг».
     *
     * Отдельно от [whenWas], потому что та считает дни в обратную сторону: на
     * будущей дате она молча уходила в ветку «давно» и печатала число там, где
     * человек ждал день недели.
     */
    fun whenWill(
        millis: Long,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        return when {
            days <= 0L -> "на сегодня"
            days == 1L -> "на завтра"
            days in 2..6 -> WEEKDAY_TO.getValue(date.dayOfWeek)
            else -> "на " + day(millis, zone)
        }
    }

    /** Точка только у сокращения месяца; разделитель и время не трогаем. */
    private fun clean(text: String): String = text.replace(".", "")
}
