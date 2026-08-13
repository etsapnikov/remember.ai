package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.domain.WeeklySummary
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * «Неделя» (спека R1.1 §6): вердикт и метрики по 7-дневному окну.
 *
 * Пороги — PRD §8, зашиты константами; «два дня подряд» для провала проверяется
 * двумя окнами со сдвигом в день.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeeklySummaryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 8, 20)

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

    private fun at(daysAgo: Long, hour: Int = 10): Long =
        today.minusDays(daysAgo).atTime(LocalTime.of(hour, 0)).atZone(zone)
            .toInstant().toEpochMilli()

    private fun write(event: String, t: Long, fields: Map<String, Any?> = emptyMap()) {
        val json = org.json.JSONObject().apply {
            put("t", t)
            put("e", event)
            fields.forEach { (k, v) -> if (v != null) put(k, v) }
        }
        file.appendText(json.toString() + "\n")
    }

    /** Обычная жизнь: записи в 5 днях, комки по 3, возвраты с ответами. */
    private fun healthyWeek() {
        (0L..4L).forEach { day ->
            write(Analytics.RECEIPT_SHOWN, at(day))
            write(Analytics.PARSE_OK, at(day), mapOf("items" to 3))
            write(Analytics.RETURN_FIRED, at(day, 12), mapOf("return" to "r$day"))
            write(Analytics.RETURN_ACTION, at(day, 13), mapOf("action" to "done"))
        }
        // Установка старше обкатки.
        write(Analytics.CAPTURE_START, at(10))
    }

    @Test
    fun `здоровая неделя — петля жива`() = runTest {
        healthyWeek()
        val report = WeeklySummary(analytics, db, zone).build(today)

        assertEquals(WeeklySummary.Verdict.ALIVE, report.verdict)
        assertEquals(WeeklySummary.Problem.NONE, report.problem)
        assertEquals(5, report.daysWithCapture)
        assertEquals(3.0, report.lumpMedian!!, 0.001)
        assertEquals(5, report.returnsShown)
        assertEquals(5, report.returnsAnswered)
    }

    @Test
    fun `сделанное и осознанный отказ считаются отдельно от пропуска`() = runTest {
        healthyWeek()
        // Один возврат человек закрыл словом «не надо», другой просто не заметил.
        write(Analytics.RETURN_FIRED, at(2, 15), mapOf("return" to "r-drop"))
        write(Analytics.RETURN_ACTION, at(2, 16), mapOf("action" to "dismiss"))
        write(Analytics.RETURN_FIRED, at(3, 15), mapOf("return" to "r-miss"))
        write(Analytics.RETURN_ACTION, at(3, 16), mapOf("action" to "miss"))

        val report = WeeklySummary(analytics, db, zone).build(today)

        assertEquals(5, report.done)
        // Пропуск раньше подмешивался в «не надо» и портил метрику «предлагаю не то».
        assertEquals(1, report.dismissed)
        assertEquals(1, report.missed)
    }

    @Test
    fun `первые три дня — рано судить`() = runTest {
        write(Analytics.CAPTURE_START, at(1))
        write(Analytics.RECEIPT_SHOWN, at(1))

        val report = WeeklySummary(analytics, db, zone).build(today)
        assertEquals(WeeklySummary.Verdict.EARLY, report.verdict)
    }

    @Test
    fun `один плохой день по kill — тревога, не провал`() = runTest {
        write(Analytics.CAPTURE_START, at(10))
        (0L..4L).forEach { day ->
            write(Analytics.RECEIPT_SHOWN, at(day))
            write(Analytics.PARSE_OK, at(day), mapOf("items" to 3))
        }
        // Возвраты показываются только сегодня и остаются без ответа: вчерашнее
        // окно этой проблемы ещё не видело.
        repeat(4) { write(Analytics.RETURN_FIRED, at(0, 12 + it % 4), mapOf("return" to "r$it")) }

        val report = WeeklySummary(analytics, db, zone).build(today)
        assertEquals(WeeklySummary.Verdict.WARN, report.verdict)
        assertEquals(WeeklySummary.Problem.RETURNS, report.problem)
    }

    @Test
    fun `kill ниже порога два дня подряд — провал`() = runTest {
        write(Analytics.CAPTURE_START, at(10))
        (0L..5L).forEach { day ->
            write(Analytics.RECEIPT_SHOWN, at(day))
            write(Analytics.PARSE_OK, at(day), mapOf("items" to 3))
            // Возвраты каждый день, ответов нет вовсе.
            write(Analytics.RETURN_FIRED, at(day, 12), mapOf("return" to "r$day"))
        }

        val report = WeeklySummary(analytics, db, zone).build(today)
        assertEquals(WeeklySummary.Verdict.FAIL, report.verdict)
    }

    @Test
    fun `команды вместо комков — тревога по второму kill`() = runTest {
        write(Analytics.CAPTURE_START, at(10))
        (0L..4L).forEach { day ->
            write(Analytics.RECEIPT_SHOWN, at(day))
            write(Analytics.PARSE_OK, at(day), mapOf("items" to 1))
            write(Analytics.RETURN_FIRED, at(day, 12), mapOf("return" to "r$day"))
            write(Analytics.RETURN_ACTION, at(day, 13), mapOf("action" to "done"))
        }

        val report = WeeklySummary(analytics, db, zone).build(today)
        // Оба окна видят медиану 1 → это уже провал, не тревога.
        assertEquals(WeeklySummary.Verdict.FAIL, report.verdict)
        assertEquals(WeeklySummary.Problem.LUMP, report.problem)
        assertEquals(1.0, report.lumpMedian!!, 0.001)
    }

    @Test
    fun `нет данных по возвратам — не считается провалом`() = runTest {
        write(Analytics.CAPTURE_START, at(10))
        (0L..4L).forEach { day ->
            write(Analytics.RECEIPT_SHOWN, at(day))
            write(Analytics.PARSE_OK, at(day), mapOf("items" to 3))
        }

        val report = WeeklySummary(analytics, db, zone).build(today)
        assertEquals(WeeklySummary.Verdict.ALIVE, report.verdict)
        assertNull(report.returnsShare)
    }

    @Test
    fun `мало дней с записью — тревога с фразой про жест`() = runTest {
        write(Analytics.CAPTURE_START, at(10))
        write(Analytics.RECEIPT_SHOWN, at(0))
        write(Analytics.PARSE_OK, at(0), mapOf("items" to 3))

        val report = WeeklySummary(analytics, db, zone).build(today)
        assertEquals(WeeklySummary.Verdict.WARN, report.verdict)
        assertEquals(WeeklySummary.Problem.DAYS, report.problem)
        assertEquals(1, report.daysWithCapture)
    }

    @Test
    fun `пустая аналитика не роняет сводку`() = runTest {
        val report = WeeklySummary(analytics, db, zone).build(today)
        assertEquals(WeeklySummary.Verdict.EARLY, report.verdict)
        assertNull(report.lumpMedian)
        assertNull(report.returnsShare)
    }
}
