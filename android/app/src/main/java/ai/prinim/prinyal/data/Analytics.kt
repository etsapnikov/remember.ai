package ai.prinim.prinyal.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Локальная аналитика под kill-критерии (PRD §F-9).
 *
 * Пишется автоматически и на устройство: решение по §8 принимается по числам,
 * записанным заранее, а не по ощущению «вроде пользуюсь» — dogfood-искажение
 * названо риском в §9 и лечится именно этим.
 *
 * Наружу не уходит ничего: файл лежит рядом с базой.
 */
class Analytics(context: Context) {

    private val file = File(context.filesDir, "analytics.jsonl")

    suspend fun log(event: String, fields: Map<String, Any?> = emptyMap()) =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("t", Instant.now().toEpochMilli())
                put("e", event)
                fields.forEach { (key, value) -> if (value != null) put(key, value) }
            }
            runCatching { file.appendText(payload.toString() + "\n") }
            Unit
        }

    suspend fun readAll(): List<JSONObject> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readLines().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()
        }
    }

    fun path(): String = file.absolutePath

    companion object {
        const val CAPTURE_START = "capture_start"
        const val CAPTURE_STOP = "capture_stop"
        const val CAPTURE_CANCEL = "capture_cancel"
        const val RECEIPT_SHOWN = "receipt_shown"
        const val PARSE_OK = "parse_ok"
        const val PARSE_FAIL = "parse_fail"
        const val RETURN_FIRED = "return_fired"
        const val RETURN_ACTION = "return_action"
        const val EDIT_ITEM = "edit_item"
        const val MISS_ITEM = "miss_item"
        const val TOPIC_ASSIGNED = "topic_assigned"
        const val TOPIC_EDITED = "topic_edited"
        const val REPLACEMENT_ADD = "replacement_add"
        const val REPLACEMENT_HIT = "replacement_hit"
        const val NOTE_APPEND = "note_append"
        const val NOTE_SPLIT = "note_split"
        /** Второй заход с рассуждениями поделил запись — то, ради чего он есть. */
        const val DEEP_PARSE_SPLIT = "deep_parse_split"
        /** …и не поделил: человек успел тронуть пункты руками. */
        const val DEEP_PARSE_SKIPPED = "deep_parse_skipped"
        const val NOTE_MERGE = "note_merge"
        const val ENTITY_ASK = "entity_ask"
        const val ENTITY_ANSWER = "entity_answer"
        const val ENTITY_DECLINE = "entity_decline"
        const val MD_RENDER_FAIL = "md_render_fail"
    }
}
