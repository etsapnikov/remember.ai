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
    private val DAY_TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale("ru"))

    fun day(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clean(Instant.ofEpochMilli(millis).atZone(zone).format(DAY))

    fun dayTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clean(Instant.ofEpochMilli(millis).atZone(zone).format(DAY_TIME))

    fun day(temporal: java.time.temporal.TemporalAccessor): String = clean(DAY.format(temporal))

    /** Точка только у сокращения месяца; разделитель и время не трогаем. */
    private fun clean(text: String): String = text.replace(".", "")
}
