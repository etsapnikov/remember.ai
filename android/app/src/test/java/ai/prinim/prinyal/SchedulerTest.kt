package ai.prinim.prinyal

import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Планировщик — единственное место, где решается, когда продукт заговорит. Правила
 * PRD §4 и приёмка F-6 проверяются здесь, а не на живом телефоне через сутки.
 */
class SchedulerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val windows = Scheduler.Windows()

    private fun at(date: String, time: String) =
        LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant()

    private fun local(instant: java.time.Instant) = LocalDateTime.ofInstant(instant, zone)

    // --- окна ---

    @Test
    fun `окно ещё впереди — сегодня`() {
        val result = Scheduler.scheduleFor(Window.EVENING, at("2026-08-03", "14:00"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-03T19:30"), local(result))
    }

    @Test
    fun `окно уже прошло — ближайшее такое же завтра`() {
        val result = Scheduler.scheduleFor(Window.MORNING, at("2026-08-03", "14:00"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-04T08:00"), local(result))
    }

    @Test
    fun `вечерний айтем в 21-50 возвращается сегодня в 22-00`() {
        val result = Scheduler.scheduleFor(Window.EVENING, at("2026-08-03", "21:50"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-03T22:00"), local(result))
    }

    @Test
    fun `вечерний айтем после 22-30 уходит на завтра, а не в ночь`() {
        val result = Scheduler.scheduleFor(Window.EVENING, at("2026-08-03", "22:40"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-04T19:30"), local(result))
    }

    @Test
    fun `завтра утром — всегда следующий день после записи`() {
        val result = Scheduler.scheduleFor(
            Window.TOMORROW_MORNING, at("2026-08-03", "06:00"), windows, zone,
        )
        assertEquals(LocalDateTime.parse("2026-08-04T08:00"), local(result))
    }

    @Test
    fun `выходные из понедельника — ближайшая суббота`() {
        val result = Scheduler.scheduleFor(Window.WEEKEND, at("2026-08-03", "10:00"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-08T11:00"), local(result))
    }

    @Test
    fun `выходные из субботнего вечера — воскресенье, а не через неделю`() {
        val result = Scheduler.scheduleFor(Window.WEEKEND, at("2026-08-08", "18:00"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-09T11:00"), local(result))
    }

    // --- «позже» ---

    @Test
    fun `позже в 21-55 не назначает 23-40, а уходит на завтра`() {
        // Приёмка F-6 дословно.
        val result = Scheduler.nextWindowAfter(at("2026-08-03", "21:55"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-04T08:00"), local(result))
    }

    @Test
    fun `позже утром уходит в день`() {
        val result = Scheduler.nextWindowAfter(at("2026-08-03", "08:05"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-03T12:30"), local(result))
    }

    @Test
    fun `позже днём уходит в вечер`() {
        val result = Scheduler.nextWindowAfter(at("2026-08-03", "13:00"), windows, zone)
        assertEquals(LocalDateTime.parse("2026-08-03T19:30"), local(result))
    }

    // --- запись пролежала в очереди ---

    @Test
    fun `разбор пришёл после того, как окно прошло — не звоним задним числом`() {
        // Записал утром «днём», сеть появилась только вечером.
        val result = Scheduler.scheduleFor(
            window = Window.DAY,
            recordedAt = at("2026-08-03", "07:00"),
            windows = windows,
            zone = zone,
            now = at("2026-08-03", "20:00"),
        )
        assertTrue(result.isAfter(at("2026-08-03", "20:00")))
        assertEquals(LocalDateTime.parse("2026-08-04T08:00"), local(result))
    }

    // --- причина возврата ---

    @Test
    fun `момент возврата опознаётся как своё окно`() {
        assertEquals(
            Window.EVENING,
            Scheduler.windowOf(at("2026-08-03", "19:32"), windows, zone),
        )
        assertEquals(
            Window.MORNING,
            Scheduler.windowOf(at("2026-08-03", "08:10"), windows, zone),
        )
    }

    @Test
    fun `момент вне окон не выдаёт ложную причину`() {
        assertEquals(null, Scheduler.windowOf(at("2026-08-03", "15:30"), windows, zone))
    }
}
