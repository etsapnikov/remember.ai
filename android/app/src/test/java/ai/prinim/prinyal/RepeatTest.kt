package ai.prinim.prinyal

import ai.prinim.prinyal.domain.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/** Повторяющиеся напоминания (Р-16.2). */
class RepeatTest {

    private val nine = LocalTime.of(9, 0)

    @Test
    fun `правило переживает запись строкой`() {
        listOf("daily", "weekly:mon", "monthly:14").forEach { wire ->
            assertEquals(wire, Repeat.of(wire)!!.wire())
        }
    }

    @Test
    fun `выдумки модели отбрасываются`() {
        listOf(null, "", "biweekly:mon", "weekly:пн", "monthly:0", "monthly:32", "каждый понедельник")
            .forEach { assertNull(it, Repeat.of(it)) }
    }

    @Test
    fun `каждый понедельник в понедельник до девяти — это сегодня`() {
        // Понедельник 24 августа 2026, восемь утра.
        val now = LocalDateTime.of(2026, 8, 24, 8, 0)
        assertEquals(
            LocalDateTime.of(2026, 8, 24, 9, 0),
            Repeat.Weekly(DayOfWeek.MONDAY).next(now, nine),
        )
    }

    @Test
    fun `каждый понедельник после девяти — это следующий`() {
        val now = LocalDateTime.of(2026, 8, 24, 9, 0)
        assertEquals(
            LocalDateTime.of(2026, 8, 31, 9, 0),
            Repeat.Weekly(DayOfWeek.MONDAY).next(now, nine),
        )
    }

    @Test
    fun `тридцать первое в феврале съезжает на последний день`() {
        val now = LocalDateTime.of(2027, 1, 31, 12, 0)
        assertEquals(
            LocalDateTime.of(2027, 2, 28, 9, 0),
            Repeat.Monthly(31).next(now, nine),
        )
    }

    @Test
    fun `следующий раз всегда строго в будущем`() {
        // Инвариант: planRepeat зовётся сразу после срабатывания, и равенство
        // моменту дало бы бесконечный цикл в ту же миллисекунду.
        val now = LocalDateTime.of(2026, 8, 24, 9, 0)
        listOf(Repeat.Daily, Repeat.Weekly(DayOfWeek.MONDAY), Repeat.Monthly(24)).forEach { rule ->
            val next = rule.next(now, nine)
            assert(next.isAfter(now)) { "${rule.wire()} вернул $next" }
        }
    }

    @Test
    fun `человеку это читается словами`() {
        assertEquals("каждый понедельник", Repeat.Weekly(DayOfWeek.MONDAY).human())
        assertEquals("14 числа каждого месяца", Repeat.Monthly(14).human())
        assertEquals("каждый день", Repeat.Daily.human())
    }
}
