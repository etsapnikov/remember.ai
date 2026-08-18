package ai.prinim.prinyal

import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.domain.Scheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Строки §4.1 — канон. Тест держит два обещания: словарь единственный (ни одной
 * строки не собирается в коде) и в ошибках нет образности.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhrasesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    private fun item(
        dueKind: DueKind = DueKind.WINDOW,
        window: Window? = Window.EVENING,
        dueAt: Long? = null,
        confidence: Confidence = Confidence.HIGH,
    ) = ItemEntity(
        id = "i1",
        noteId = "n1",
        type = "do",
        text = "купить капли",
        dueKind = dueKind.wire,
        window = window?.wire,
        dueAt = dueAt,
        state = ItemState.PLANNED.wire,
        confidence = confidence.wire,
    )

    @Test
    fun `план окна берётся из словаря`() {
        assertEquals("напомню вечером", Phrases.plan(context, item(), zone))
        assertEquals(
            "напомню завтра утром",
            Phrases.plan(context, item(window = Window.TOMORROW_MORNING), zone),
        )
    }

    @Test
    fun `без срока продукт обещает ровно то, что сделает`() {
        val text = Phrases.plan(context, item(dueKind = DueKind.NONE, window = null), zone)
        assertEquals("просто сохраню", text)
    }

    @Test
    fun `сегодня в плане стоит время`() {
        // Миллисекунды: тест раньше клал секунды и тем закреплял ошибку единиц,
        // из-за которой любая явная дата уезжала в 1970 год.
        val at = LocalDateTime.now(zone).withHour(19).withMinute(0)
            .atZone(zone).toInstant().toEpochMilli()
        val text = Phrases.plan(context, item(dueKind = DueKind.EXACT, window = null, dueAt = at), zone)
        assertEquals("верну в 19:00", text)
    }

    @Test
    fun `в другой день в плане стоит дата, а не время`() {
        // «Вернусь в 09:00» через три недели не отвечает на вопрос человека,
        // «вернусь 10 сен» — отвечает (Д-8).
        val at = LocalDateTime.now(zone).plusDays(24).withHour(9).withMinute(0)
            .atZone(zone).toInstant().toEpochMilli()
        val text = Phrases.plan(context, item(dueKind = DueKind.EXACT, window = null, dueAt = at), zone)
        assertTrue("в плане нет даты: $text", text.startsWith("вернусь "))
        assertFalse("в плане осталось время: $text", text.contains(":"))
    }

    @Test
    fun `exact без времени не притворяется, что время есть`() {
        val text = Phrases.plan(context, item(dueKind = DueKind.EXACT, window = null), zone)
        assertEquals("просто сохраню", text)
    }

    @Test
    fun `низкая уверенность помечается, высокая — нет`() {
        assertNotNull(Phrases.uncertainty(context, item(confidence = Confidence.LOW)))
        assertNull(Phrases.uncertainty(context, item(confidence = Confidence.HIGH)))
        assertEquals(
            "не уверен, что понял — проверь",
            Phrases.uncertainty(context, item(confidence = Confidence.LOW)),
        )
    }

    @Test
    fun `у возврата всегда есть причина`() {
        val windows = Scheduler.Windows()
        val evening = LocalDateTime.parse("2026-08-03T19:30").atZone(zone).toInstant()
        assertEquals(
            "вечер — ты просил вернуть",
            Phrases.reason(context, item(), attempt = 1, firedAt = evening, windows = windows, zone = zone),
        )
    }

    @Test
    fun `второй заход называет себя вторым`() {
        val windows = Scheduler.Windows()
        assertEquals(
            "возвращаю второй раз",
            Phrases.reason(context, item(), 2, Instant.now(), windows, zone),
        )
    }

    @Test
    fun `у каждой деградации есть свой честный текст`() {
        assertEquals("на ключе DeepSeek кончились средства", Phrases.degraded(context, "llm_no_balance"))
        assertEquals(
            "нет связи с сервером — записи сохраняются, разберу позже",
            Phrases.degraded(context, "no_server"),
        )
        // Незнакомый код не оставляет пользователя без объяснения.
        assertEquals(
            "не смог разобрать — посмотри сам, верну вечером",
            Phrases.degraded(context, "llm_something_new"),
        )
        assertNull(Phrases.degraded(context, null))
    }

    @Test
    fun `в строках ошибок нет образности`() {
        // §4.1, п. 1: метафоры и игривость запрещены, особенно в ошибках — уставший
        // человек в шторке должен понять смысл за секунду, не разгадывая тон.
        // Слова ищем целиком: «настройках» содержит «ой», и это не повод падать.
        val forbidden = listOf("спит", "уснул", "устал", "проснулся", "ой", "упс", "ага", "увы")
        val codes = listOf(
            "llm_no_balance", "no_server", "asr_failed", "asr_empty", "llm_disabled", "llm_error",
        )

        codes.forEach { code ->
            val text = Phrases.degraded(context, code).orEmpty()
            val words = text.lowercase().split(Regex("[^\\p{L}]+")).filter(String::isNotEmpty)

            forbidden.forEach { bad ->
                assert(bad !in words) { "строка «$text» нарушает §4.1: содержит «$bad»" }
            }
            assert(!text.contains("!")) { "строка «$text» повышает голос" }
            assert(text.none { it.code > 0x2000 && it.code !in 0x2010..0x2060 }) {
                "строка «$text» содержит эмодзи — §4.1 этого не разрешает"
            }
        }
    }

    @Test
    fun `подтверждение позже называет конкретный момент`() {
        val tomorrowMorning = LocalDateTime.now(zone).plusDays(1)
            .withHour(8).withMinute(0).atZone(zone).toInstant()
        val text = Phrases.laterConfirmation(context, tomorrowMorning, zone)
        assertEquals("ок, верну завтра утром", text)
    }
}
