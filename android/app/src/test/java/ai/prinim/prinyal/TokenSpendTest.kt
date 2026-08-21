package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.domain.TokenSpend
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Счёт за модель (Р-16.4): токены — факт, деньги — оценка по вбитому прайсу. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenSpendTest {

    private val now: Instant = Instant.parse("2026-08-21T12:00:00Z")

    private fun usage(kind: String, cached: Int, fresh: Int, out: Int, daysAgo: Long = 0) =
        JSONObject()
            .put("e", Analytics.LLM_USAGE)
            .put("t", now.minusSeconds(daysAgo * 24 * 3600).toEpochMilli())
            .put("kind", kind)
            .put("cached_in", cached)
            .put("fresh_in", fresh)
            .put("out", out)

    @Test
    fun `кэшированный вход считается своей ценой`() {
        // Иначе счёт завышен в разы: системный промпт один и тот же на все
        // записи, и кэша в разборе большинство.
        val cachedOnly = TokenSpend.of(listOf(usage("parse", cached = 1_000_000, fresh = 0, out = 0)))
        val freshOnly = TokenSpend.of(listOf(usage("parse", cached = 0, fresh = 1_000_000, out = 0)))
        assertEquals(TokenSpend.IN_CACHED, cachedOnly.dollars, 1e-9)
        assertEquals(TokenSpend.IN_FRESH, freshOnly.dollars, 1e-9)
        assertTrue("кэш обязан быть дешевле", cachedOnly.dollars < freshOnly.dollars)
    }

    @Test
    fun `неделя не тянет за собой старое`() {
        val events = listOf(
            usage("parse", 0, 1_000_000, 0, daysAgo = 1),
            usage("parse", 0, 1_000_000, 0, daysAgo = 30),
        )
        val week = TokenSpend.of(events, since = now.minusSeconds(7 * 24 * 3600))
        val all = TokenSpend.of(events)
        assertEquals(1, week.calls)
        assertEquals(2, all.calls)
    }

    @Test
    fun `по видам видно, за что платим`() {
        val spend = TokenSpend.of(
            listOf(
                usage("parse", 0, 1_000_000, 0),
                usage("interview", 0, 1_000_000, 0),
                usage("interview", 0, 1_000_000, 0),
            )
        )
        assertEquals(setOf("parse", "interview"), spend.byKind.keys)
        assertEquals(
            "пинг-понг стоил вдвое дороже разбора",
            spend.byKind.getValue("parse") * 2,
            spend.byKind.getValue("interview"),
            1e-9,
        )
    }

    @Test
    fun `чужие события в счёт не идут`() {
        val events = listOf(
            JSONObject().put("e", Analytics.PARSE_OK).put("t", now.toEpochMilli()),
            usage("parse", 0, 100, 50),
        )
        assertEquals(1, TokenSpend.of(events).calls)
        assertEquals(150L, TokenSpend.of(events).tokens)
    }

    @Test
    fun `числа читаются глазом`() {
        // Неразрывный, а не обычный: число не должно рваться переносом.
        assertEquals("12\u00A0340", TokenSpend.tokens(12_340))
        assertEquals("$0.0143", TokenSpend.money(0.01432))
    }
}
