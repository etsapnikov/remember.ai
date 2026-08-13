package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.PrinyalDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Сводка недели (спека R1.1 §6): экран отвечает на один вопрос — «петля жива?».
 *
 * Метрики считаются по 7-дневному окну из `analytics.jsonl`. Пороги записаны здесь
 * констанами и в подписи порога на экране: решение по kill-критериям принимается по
 * числам, зафиксированным заранее, а не по самочувствию (PRD §8/§9).
 *
 * Отступление от таблицы дизайнера, по её же правилу «пороги из PRD §8»:
 * возвраты — порог 50% (в макете 40), «не надо» — тревога выше 20% (в макете 60).
 */
class WeeklySummary(
    private val analytics: Analytics,
    private val db: PrinyalDb,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    enum class Verdict { EARLY, ALIVE, WARN, FAIL }

    /** Что именно сломано — из этого собирается фраза под вердиктом. */
    enum class Problem { NONE, RETURNS, LUMP, DAYS }

    data class Report(
        val verdict: Verdict,
        val problem: Problem,
        /** Возвраты: любой ответ ÷ показанные. null — показов не было. */
        val returnsAnswered: Int,
        val returnsShown: Int,
        /** Медиана пунктов в разобранной записи. null — разборов не было. */
        val lumpMedian: Double?,
        val daysWithCapture: Int,
        val daysWindow: Int,
        /** Медиана записей в активный день. null — записей не было. */
        val perDayMedian: Int?,
        /**
         * «Не надо» — осознанный отказ. Считается только явный отказ; «не ответил»
         * живёт отдельно в [missed]. Раньше они складывались в одно число, и
         * метрика «продукт предлагает не то» портилась любым пропущенным
         * уведомлением.
         */
        val dismissed: Int,
        /** Возврат показан, ответа не было. */
        val missed: Int,
        /** Сделано по возврату. Единственное число, которое человек видит крупно. */
        val done: Int,
    ) {
        val returnsShare: Double? =
            if (returnsShown == 0) null else returnsAnswered.toDouble() / returnsShown
    }

    companion object {
        const val WINDOW_DAYS = 7
        const val WARMUP_DAYS = 3

        // Пороги решений — PRD §8.
        const val RETURNS_MIN = 0.5
        const val LUMP_MIN = 2.0
        const val DAYS_MIN = 4
        const val DISMISSED_MAX = 0.2
    }

    suspend fun build(today: LocalDate = LocalDate.now(zone)): Report =
        withContext(Dispatchers.IO) {
            val events = analytics.readAll()

            val installDay = events.minOfOrNull { dayOf(it.optLong("t")) } ?: today
            if (ChronoUnit.DAYS.between(installDay, today) < WARMUP_DAYS) {
                val window = window(events, today)
                return@withContext window.toReport(Verdict.EARLY, Problem.NONE)
            }

            val current = window(events, today)
            val alarms = current.failedKills()

            val verdict = when {
                alarms.isEmpty() -> if (current.warnings()) Verdict.WARN else Verdict.ALIVE
                // Провал — kill ниже порога два дня подряд, не единичный плохой день.
                window(events, today.minusDays(1)).failedKills()
                    .intersect(alarms).isNotEmpty() -> Verdict.FAIL
                else -> Verdict.WARN
            }

            val problem = when {
                Problem.DAYS in alarmsAsProblems(current) -> Problem.DAYS
                Problem.RETURNS in alarmsAsProblems(current) -> Problem.RETURNS
                Problem.LUMP in alarmsAsProblems(current) -> Problem.LUMP
                else -> Problem.NONE
            }

            current.toReport(verdict, problem)
        }

    // --- окно ---

    private class WindowStats(
        val returnsAnswered: Int,
        val returnsShown: Int,
        val lumpMedian: Double?,
        val daysWithCapture: Int,
        val perDayMedian: Int?,
        val dismissed: Int,
        val missed: Int,
        val done: Int,
    ) {
        /** Kill-критерии, проваленные в этом окне. Нет данных — не провал. */
        fun failedKills(): Set<String> = buildSet {
            if (returnsShown > 0 && returnsAnswered.toDouble() / returnsShown < RETURNS_MIN) {
                add("returns")
            }
            if (lumpMedian != null && lumpMedian < LUMP_MIN) add("lump")
        }

        fun warnings(): Boolean {
            if (daysWithCapture < DAYS_MIN) return true
            val answered = returnsAnswered
            if (answered > 0 && dismissed.toDouble() / answered > DISMISSED_MAX) return true
            return false
        }

        fun toReport(verdict: Verdict, problem: Problem) = Report(
            verdict = verdict,
            problem = problem,
            returnsAnswered = returnsAnswered,
            returnsShown = returnsShown,
            lumpMedian = lumpMedian,
            daysWithCapture = daysWithCapture,
            daysWindow = WINDOW_DAYS,
            perDayMedian = perDayMedian,
            dismissed = dismissed,
            missed = missed,
            done = done,
        )
    }

    private fun alarmsAsProblems(stats: WindowStats): Set<Problem> = buildSet {
        if ("returns" in stats.failedKills()) add(Problem.RETURNS)
        if ("lump" in stats.failedKills()) add(Problem.LUMP)
        if (stats.daysWithCapture < DAYS_MIN) add(Problem.DAYS)
    }

    private fun window(events: List<JSONObject>, until: LocalDate): WindowStats {
        val from = until.minusDays(WINDOW_DAYS.toLong() - 1)

        fun inWindow(event: JSONObject): Boolean {
            val day = dayOf(event.optLong("t"))
            return !day.isBefore(from) && !day.isAfter(until)
        }

        val receipts = events.filter { it.optString("e") == Analytics.RECEIPT_SHOWN && inWindow(it) }
        val parses = events.filter { it.optString("e") == Analytics.PARSE_OK && inWindow(it) }
        val shown = events.count {
            it.optString("e") == Analytics.RETURN_FIRED && it.has("return") && inWindow(it)
        }
        val answers = events.filter { it.optString("e") == Analytics.RETURN_ACTION && inWindow(it) }

        val byDay = receipts.groupBy { dayOf(it.optLong("t")) }
        val perDay = byDay.values.map { it.size }.sorted()

        val itemCounts = parses.map { it.optInt("items") }.sorted()

        return WindowStats(
            returnsAnswered = answers.size,
            returnsShown = shown,
            lumpMedian = medianOf(itemCounts.map(Int::toDouble)),
            daysWithCapture = byDay.size,
            perDayMedian = medianOf(perDay.map(Int::toDouble))?.toInt(),
            dismissed = answers.count { it.optString("action") == "dismiss" },
            missed = answers.count { it.optString("action") == "miss" },
            done = answers.count { it.optString("action") == "done" },
        )
    }

    private fun medianOf(sorted: List<Double>): Double? {
        if (sorted.isEmpty()) return null
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
