package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.PrinyalDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Сводка недели под kill-критерии (PRD §F-9, §8).
 *
 * Считается по `analytics.jsonl`, а не по ощущениям: риск «сам себе прощаю пропуски»
 * назван в §9, и лечится он только тем, что числа пишутся автоматически, а пороги
 * записаны заранее.
 *
 * Правило честности §8: первые три дня — обкатка, в метрики не идут.
 */
class WeeklySummary(
    private val analytics: Analytics,
    private val db: PrinyalDb,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    data class Report(
        val daysWindow: Int,
        val daysWithCapture: Int,
        val notesPerDay: Double,
        /** Медиана пунктов на запись. < 1.5 — тревога по kill-критерию 2. */
        val itemsPerNoteMedian: Double,
        val returnsActedShare: Double,
        val dismissedShare: Double,
        val editedShare: Double,
        val parseLatencyMedianMs: Long,
        val returnDriftWithin2MinShare: Double,
    ) {
        /** Медиана < 1.5 означает, что диктуются команды, а не комки — построен Siri-клон. */
        val siriCloneAlarm: Boolean get() = itemsPerNoteMedian < 1.5
    }

    suspend fun build(window: Int = 14, warmupDays: Int = 3): Report =
        withContext(Dispatchers.IO) {
            val events = analytics.readAll()
            val today = LocalDate.now(zone)
            val from = today.minusDays(window.toLong() - 1)
            val countedFrom = from.plusDays(warmupDays.toLong())

            fun dayOf(millis: Long): LocalDate =
                Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

            fun counted(millis: Long): Boolean {
                val day = dayOf(millis)
                return !day.isBefore(countedFrom) && !day.isAfter(today)
            }

            val receipts = events.filter { it.optString("e") == Analytics.RECEIPT_SHOWN }
                .filter { counted(it.optLong("t")) }
            val parses = events.filter { it.optString("e") == Analytics.PARSE_OK }
                .filter { counted(it.optLong("t")) }
            val fired = events.filter { it.optString("e") == Analytics.RETURN_FIRED }
                .filter { counted(it.optLong("t")) && it.has("return") }
            val actions = events.filter { it.optString("e") == Analytics.RETURN_ACTION }
                .filter { counted(it.optLong("t")) }
            val edits = events.filter { it.optString("e") == Analytics.EDIT_ITEM }
                .filter { counted(it.optLong("t")) }

            val daysWithCapture = receipts.map { dayOf(it.optLong("t")) }.distinct().size
            val countedDays = (window - warmupDays).coerceAtLeast(1)

            val itemCounts = parses.map { it.optInt("items").toDouble() }
            val latencies = parses.map { (it.optInt("asr_ms") + it.optInt("llm_ms")).toLong() }

            // «Сделано» и «позже» — это замкнувшаяся петля; «не надо» — мусор разбора.
            val acted = actions.count { it.optString("action") in setOf("done", "later") }
            val dismissed = actions.count { it.optString("action") in setOf("dismiss", "miss") }

            val drifts = fired.mapNotNull { if (it.has("drift_ms")) it.optLong("drift_ms") else null }
            val withinTwoMinutes = drifts.count { kotlin.math.abs(it) <= 120_000 }

            val totalItems = db.items().all().size

            Report(
                daysWindow = countedDays,
                daysWithCapture = daysWithCapture,
                notesPerDay = if (daysWithCapture == 0) 0.0
                else receipts.size.toDouble() / daysWithCapture,
                itemsPerNoteMedian = median(itemCounts),
                returnsActedShare = share(acted, fired.size),
                dismissedShare = share(dismissed, fired.size),
                editedShare = share(edits.size, totalItems),
                parseLatencyMedianMs = median(latencies.map(Long::toDouble)).toLong(),
                returnDriftWithin2MinShare = share(withinTwoMinutes, drifts.size),
            )
        }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun share(part: Int, total: Int): Double =
        if (total == 0) 0.0 else part.toDouble() / total
}
