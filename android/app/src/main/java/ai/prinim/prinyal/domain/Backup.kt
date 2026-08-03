package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.NoteEntity
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.ReturnEntity
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * Экспорт и импорт всей базы одним файлом (PRD §F-8).
 *
 * Это страховка dogfood-корпуса: две недели личного прогона — единственные данные,
 * по которым принимается решение §8, и потерять их из-за сброса телефона нельзя.
 *
 * Приёмка: файл реимпортируется на чистой установке — поэтому в нём лежат и записи,
 * и пункты, и история возвратов, и аналитика.
 */
class Backup(
    private val context: Context,
    private val db: PrinyalDb,
    private val analytics: Analytics,
) {

    suspend fun export(): File = withContext(Dispatchers.IO) {
        val root = JSONObject().apply {
            put("version", 1)
            put("exported_at", Instant.now().toEpochMilli())
            put("notes", JSONArray(db.notes().all().map(::noteJson)))
            put("items", JSONArray(db.items().all().map(::itemJson)))
            put("returns", JSONArray(db.returns().all().map(::returnJson)))
            put("analytics", JSONArray(analytics.readAll()))
        }

        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "prinyal-${Instant.now().toEpochMilli()}.json")
        file.writeText(root.toString(2))
        file
    }

    suspend fun import(file: File): Int = withContext(Dispatchers.IO) {
        val root = JSONObject(file.readText())

        val notes = root.optJSONArray("notes") ?: JSONArray()
        for (index in 0 until notes.length()) {
            db.notes().insert(noteOf(notes.getJSONObject(index)))
        }

        val items = root.optJSONArray("items") ?: JSONArray()
        val parsedItems = buildList {
            for (index in 0 until items.length()) add(itemOf(items.getJSONObject(index)))
        }
        if (parsedItems.isNotEmpty()) db.items().insertAll(parsedItems)

        val returns = root.optJSONArray("returns") ?: JSONArray()
        for (index in 0 until returns.length()) {
            db.returns().insert(returnOf(returns.getJSONObject(index)))
        }

        notes.length()
    }

    // --- сериализация ---

    private fun noteJson(note: NoteEntity) = JSONObject().apply {
        put("id", note.id)
        put("created_at", note.createdAt)
        put("audio_path", note.audioPath)
        put("transcript", note.transcript)
        put("status", note.status)
        put("duration_ms", note.durationMs)
        put("source", note.source)
        put("degraded", note.degraded)
        put("attempts", note.attempts)
    }

    private fun noteOf(json: JSONObject) = NoteEntity(
        id = json.getString("id"),
        createdAt = json.getLong("created_at"),
        audioPath = json.optString("audio_path"),
        transcript = json.optStringOrNull("transcript"),
        status = json.optString("status"),
        durationMs = json.optLong("duration_ms"),
        source = json.optString("source"),
        degraded = json.optStringOrNull("degraded"),
        attempts = json.optInt("attempts"),
    )

    private fun itemJson(item: ItemEntity) = JSONObject().apply {
        put("id", item.id)
        put("note_id", item.noteId)
        put("type", item.type)
        put("text", item.text)
        put("who", item.who)
        put("due_kind", item.dueKind)
        put("window", item.window)
        put("due_at", item.dueAt)
        put("state", item.state)
        put("confidence", item.confidence)
        put("raw_span", item.rawSpan)
        put("position", item.position)
        put("edited", item.edited)
    }

    private fun itemOf(json: JSONObject) = ItemEntity(
        id = json.getString("id"),
        noteId = json.getString("note_id"),
        type = json.optString("type"),
        text = json.optString("text"),
        who = json.optStringOrNull("who"),
        dueKind = json.optString("due_kind"),
        window = json.optStringOrNull("window"),
        dueAt = if (json.isNull("due_at")) null else json.optLong("due_at"),
        state = json.optString("state"),
        confidence = json.optString("confidence"),
        rawSpan = json.optStringOrNull("raw_span"),
        position = json.optInt("position"),
        edited = json.optBoolean("edited"),
    )

    private fun returnJson(entity: ReturnEntity) = JSONObject().apply {
        put("id", entity.id)
        put("item_id", entity.itemId)
        put("scheduled_at", entity.scheduledAt)
        put("fired_at", entity.firedAt)
        put("action", entity.action)
        put("attempt", entity.attempt)
    }

    private fun returnOf(json: JSONObject) = ReturnEntity(
        id = json.getString("id"),
        itemId = json.getString("item_id"),
        scheduledAt = json.getLong("scheduled_at"),
        firedAt = if (json.isNull("fired_at")) null else json.optLong("fired_at"),
        action = json.optStringOrNull("action"),
        attempt = json.optInt("attempt", 1),
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }
}
