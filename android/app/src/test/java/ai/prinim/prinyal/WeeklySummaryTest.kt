package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.domain.WeeklySummary
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Сводка §F-9 считается корректно на синтетической неделе данных — это дословная
 * приёмка. Здесь же проверяется правило честности §8: первые три дня не в метриках.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklySummaryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var db: PrinyalDb
    private lateinit var analytics: Analytics
    private lateinit var file: File

    @Before
    fun setUp() {
        db = PrinyalDb.inMemory(context)
        analytics = Analytics(context)
        file = File(context.filesDir, "analytics.jsonl")
        file.delete()
    }

    @After
    fun tearDown() {
        db.close()
        file.delete()
    }

    /** Момент N дней назад — так синтетическая неделя не зависит от даты прогона. */
    private fun daysAgo(days: Long, hour: Int = 10): Long =
        LocalDate.now(zone).minusDays(days).atTime(LocalTime.of(hour, 0))
            .atZone(zone).toInstant().toEpochMilli()

    private suspend fun write(event: String, at: Long, fields: Map<String, Any?> = emptyMap()) {
        // Analytics ставит собственный timestamp, поэтому синтетику пишем напрямую.
        val json = org.json.JSONObject().apply {
            put("t", at)
            put("e", event)
            fields.forEach { (k, v) -> if (v != null) put(k, v) }
        }
        file.appendText(json.toString() + "\n")
    }

    @Test
    fun `считает дни с записью, медиану пунктов и долю ответов`() = runTest {
        // Дни 4..10 назад — внутри окна и после обкатки.
        listOf(4L, 5L, 6L, 7L).forEach { day ->
            write(Analytics.RECEIPT_SHOWN, daysAgo(day))
            write(Analytics.PARSE_OK, daysAgo(day), mapOf("items" to 3, "asr_ms" to 1000, "llm_ms" to 900))
        }
        write(Analytics.RECEIPT_SHOWN, daysAgo(4, hour = 18))
        write(Analytics.PARSE_OK, daysAgo(4, hour = 18), mapOf("items" to 1, "asr_ms" to 800, "llm_ms" to 700))

        repeat(4) { index ->
            write(Analytics.RETURN_FIRED, daysAgo(5), mapOf("return" to "r$index", "drift_ms" to 30_000))
        }
        write(Analytics.RETURN_ACTION, daysAgo(5), mapOf("action" to "done"))
        write(Analytics.RETURN_ACTION, daysAgo(5), mapOf("action" to "later"))
        write(Analytics.RETURN_ACTION, daysAgo(5), mapOf("action" to "dismiss"))

        val report = WeeklySummary(analytics, db, zone).build()

        assertEquals(4, report.daysWithCapture)
        assertEquals(11, report.daysWindow)
        // Пункты: 3,3,3,3,1 → медиана 3.
        assertEquals(3.0, report.itemsPerNoteMedian, 0.001)
        // 5 записей за 4 дня.
        assertEquals(1.25, report.notesPerDay, 0.001)
        // done+later = 2 из 4 возвратов.
        assertEquals(0.5, report.returnsActedShare, 0.001)
        assertEquals(0.25, report.dismissedShare, 0.001)
        assertEquals(1900L, report.parseLatencyMedianMs)
        assertEquals(1.0, report.returnDriftWithin2MinShare, 0.001)
        assertFalse(report.siriCloneAlarm)
    }

    @Test
    fun `первые три дня окна в метрики не идут`() = runTest {
        // День 13 назад — начало окна, попадает в обкатку.
        write(Analytics.RECEIPT_SHOWN, daysAgo(13))
        write(Analytics.PARSE_OK, daysAgo(13), mapOf("items" to 5))

        val report = WeeklySummary(analytics, db, zone).build()

        assertEquals(0, report.daysWithCapture)
        assertEquals(0.0, report.itemsPerNoteMedian, 0.001)
    }

    @Test
    fun `медиана ниже полутора поднимает тревогу по kill-критерию 2`() = runTest {
        listOf(4L, 5L, 6L).forEach { day ->
            write(Analytics.RECEIPT_SHOWN, daysAgo(day))
            write(Analytics.PARSE_OK, daysAgo(day), mapOf("items" to 1))
        }

        val report = WeeklySummary(analytics, db, zone).build()

        assertEquals(1.0, report.itemsPerNoteMedian, 0.001)
        assertTrue(report.siriCloneAlarm)
    }

    @Test
    fun `пустая аналитика не роняет сводку`() = runTest {
        val report = WeeklySummary(analytics, db, zone).build()
        assertEquals(0, report.daysWithCapture)
        assertEquals(0.0, report.returnsActedShare, 0.001)
    }
}
