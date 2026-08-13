package ai.prinim.prinyal.returns

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * След жизни возврата: план → аларм → уведомление → ответ.
 *
 * Ни один возврат ни разу не сработал на Honor, и три захода мы чинили это
 * вслепую. Причина в том, что нам нечем отличить «аларм не сработал» от
 * «сработал, но уведомление задушено» — logcat на этой прошивке зашифрован, а
 * обычная аналитика пишет только факт показа.
 *
 * Поэтому здесь отдельный след, и он **не аналитика**: аналитика отвечает на
 * вопрос «жив ли продукт», а этот файл — на вопрос «доехал ли конкретный
 * возврат и где встал». Смешивать их нельзя, иначе диагностика начнёт влиять
 * на kill-метрики.
 */
object ReturnDiag {

    // Шаги, на которых возврат может застрять.
    const val SCHEDULED = "scheduled"
    const val ALARM = "alarm"
    const val SHOWN = "shown"
    const val SKIPPED = "skipped"
    const val CATCHUP = "catchup"
    const val ANSWERED = "answered"
    const val CANCELLED = "cancelled"

    /** Хвоста хватает на несколько дней наблюдения; файл не должен расти вечно. */
    private const val MAX_LINES = 600

    fun file(context: Context) = File(context.filesDir, "returns-diag.jsonl")

    fun log(
        context: Context,
        returnId: String,
        step: String,
        extra: Map<String, Any?> = emptyMap(),
    ) {
        runCatching {
            val line = JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("id", returnId)
                put("step", step)
                extra.forEach { (k, v) -> if (v != null) put(k, v) }
            }
            val target = file(context)
            target.appendText(line.toString() + "\n")
            trim(target)
        }
    }

    private fun trim(target: File) {
        val lines = target.readLines()
        if (lines.size <= MAX_LINES) return
        target.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
    }

    /**
     * Один возврат целиком: что обещали и что случилось.
     *
     * @param plannedAt время, на которое ставили аларм
     * @param alarmAt когда аларм действительно сработал; null — не сработал ни разу
     * @param shownAt когда показали уведомление
     * @param viaCatchup показано страховочным воркером, а не аларм ом — значит
     *        точный аларм прошивка съела
     */
    data class Trace(
        val id: String,
        val plannedAt: Instant?,
        val alarmAt: Instant?,
        val shownAt: Instant?,
        val answeredAt: Instant?,
        val viaCatchup: Boolean,
        val note: String?,
    ) {
        /** Опоздание уведомления против плана. null — показа не было. */
        val lateMs: Long?
            get() = if (plannedAt != null && shownAt != null) {
                shownAt.toEpochMilli() - plannedAt.toEpochMilli()
            } else {
                null
            }
    }

    /** Последние возвраты, свежие сверху. */
    fun recent(context: Context, limit: Int = 12): List<Trace> {
        val target = file(context)
        if (!target.isFile) return emptyList()

        val byId = LinkedHashMap<String, MutableMap<String, Any?>>()
        target.readLines().forEach { raw ->
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val id = json.optString("id").ifBlank { return@forEach }
            val row = byId.getOrPut(id) { mutableMapOf() }
            val at = json.optLong("t")
            when (json.optString("step")) {
                SCHEDULED -> row["planned"] = json.optLong("at", at)
                ALARM -> row["alarm"] = at
                SHOWN -> {
                    row["shown"] = at
                    if (json.optBoolean("catchup")) row["catchup"] = true
                }
                CATCHUP -> { row["shown"] = at; row["catchup"] = true }
                ANSWERED -> row["answered"] = at
                SKIPPED, CANCELLED -> row["note"] = json.optString("why").ifBlank { "—" }
            }
        }

        return byId.entries.reversed().take(limit).map { (id, row) ->
            Trace(
                id = id,
                plannedAt = (row["planned"] as? Long)?.let(Instant::ofEpochMilli),
                alarmAt = (row["alarm"] as? Long)?.let(Instant::ofEpochMilli),
                shownAt = (row["shown"] as? Long)?.let(Instant::ofEpochMilli),
                answeredAt = (row["answered"] as? Long)?.let(Instant::ofEpochMilli),
                viaCatchup = row["catchup"] == true,
                note = row["note"] as? String,
            )
        }
    }
}
