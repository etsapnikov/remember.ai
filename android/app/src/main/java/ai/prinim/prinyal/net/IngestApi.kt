package ai.prinim.prinyal.net

import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteKind
import ai.prinim.prinyal.data.Window
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Разобранный пункт в том виде, в каком его прислал бэкенд. */
data class ParsedItem(
    val type: ItemType,
    val text: String,
    val who: String?,
    val dueKind: DueKind,
    val window: Window?,
    val dueAt: Long?,
    val confidence: Confidence,
    val rawSpan: String?,
    /** id существующего пункта, который модель уточняет (Р-14.3). */
    val ref: String? = null,
)

data class ParseResult(
    val transcript: String,
    val items: List<ParsedItem>,
    /** Что это за запись (Р-14.4). null — модель не сказала или ответ старый. */
    val noteKind: NoteKind? = null,
    /** Имя раздела как его назвала модель. Сопоставление с базой — в репозитории. */
    val topic: String? = null,
    /** Люди, упомянутые в записи, — сырьё для доспроса (Р-14.7). */
    val entities: List<String> = emptyList(),
    /** Тело заметки в markdown — только у идей (Р-14.5). */
    val bodyMd: String? = null,
    /**
     * Вторая заметка, если запись содержала две несвязанные темы (Р-15.5).
     * null — обычный случай, и он же случай по умолчанию.
     */
    val second: ParseResult? = null,
    /**
     * Срок, названный для всей записи (Р-15.7): «верни мне это всё в
     * понедельник». Применяется ко всем живым пунктам разом.
     */
    val noteDueAt: Long? = null,
    /** Связи с прежними записями (Р-15.11). Пусто — обычный и хороший случай. */
    val links: List<ai.prinim.prinyal.domain.LinkValidator.Link> = emptyList(),
    /** Код деградации §6 или null. Строку подбирает UI из strings.xml. */
    val degraded: String?,
    val asrMs: Int,
    val llmMs: Int,
    val llmRetries: Int,
)

/**
 * Ответ конвейера. Разделение важно для очереди: `Retryable` уходит в backoff,
 * `Fatal` — нет, иначе телефон будет всю ночь долбить сервер тишиной.
 */
sealed interface IngestOutcome {
    data class Ok(val result: ParseResult) : IngestOutcome
    data class Fatal(val code: String) : IngestOutcome
    data class Retryable(val code: String) : IngestOutcome
}

class IngestApi(
    private val client: OkHttpClient = defaultClient(),
) {

    fun ingest(
        baseUrl: String,
        token: String,
        noteId: String,
        audio: File,
        createdAtSeconds: Long,
        tzOffsetMinutes: Int,
    ): IngestOutcome {
        if (baseUrl.isBlank() || token.isBlank()) return IngestOutcome.Retryable("not_configured")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("note_id", noteId)
            .addFormDataPart("client_ts", createdAtSeconds.toString())
            .addFormDataPart("tz_offset_minutes", tzOffsetMinutes.toString())
            .addFormDataPart("audio", audio.name, audio.asRequestBody(AUDIO_TYPE))
            .build()

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/ingest")
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> IngestOutcome.Ok(parseBody(text))
                    // Тишина, битое аудио, слишком длинный клип — ретрай не поможет.
                    response.code in setOf(400, 401, 413, 422) ->
                        IngestOutcome.Fatal(errorCode(text, response.code))
                    else -> IngestOutcome.Retryable(errorCode(text, response.code))
                }
            }
        } catch (e: IOException) {
            IngestOutcome.Retryable("no_server")
        } catch (e: IllegalArgumentException) {
            IngestOutcome.Fatal("bad_response")
        }
    }

    /**
     * Разбор уже распознанного текста — путь для ASR на устройстве.
     *
     * Аудио никуда не уходит: телефон распознал сам, наружу летит только текст.
     */
    fun parse(
        baseUrl: String,
        token: String,
        noteId: String,
        transcript: String,
        createdAtSeconds: Long,
        tzOffsetMinutes: Int,
    ): IngestOutcome {
        if (baseUrl.isBlank() || token.isBlank()) return IngestOutcome.Retryable("not_configured")

        val payload = JSONObject().apply {
            put("note_id", noteId)
            put("transcript", transcript)
            put("client_ts", createdAtSeconds)
            put("tz_offset_minutes", tzOffsetMinutes)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/parse")
            .addHeader("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> IngestOutcome.Ok(parseBody(text))
                    response.code in setOf(400, 401, 413, 422) ->
                        IngestOutcome.Fatal(errorCode(text, response.code))
                    else -> IngestOutcome.Retryable(errorCode(text, response.code))
                }
            }
        } catch (e: IOException) {
            IngestOutcome.Retryable("no_server")
        } catch (e: IllegalArgumentException) {
            IngestOutcome.Fatal("bad_response")
        }
    }

    fun health(baseUrl: String, token: String): Boolean {
        if (baseUrl.isBlank()) return false
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/health")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun errorCode(body: String, status: Int): String =
        runCatching { JSONObject(body).optString("error").ifBlank { "http_$status" } }
            .getOrDefault("http_$status")

    private fun parseBody(body: String): ParseResult {
        val root = JSONObject(body)
        val meta = root.optJSONObject("meta") ?: JSONObject()
        val itemsJson = root.optJSONArray("items")

        val items = buildList {
            for (index in 0 until (itemsJson?.length() ?: 0)) {
                val raw = itemsJson!!.getJSONObject(index)
                add(
                    ParsedItem(
                        type = ItemType.of(raw.optString("type")),
                        text = raw.optString("text"),
                        who = raw.optStringOrNull("who"),
                        dueKind = DueKind.of(raw.optString("due_kind")),
                        window = Window.of(raw.optStringOrNull("window")),
                        dueAt = if (raw.isNull("due_at")) null else raw.optLong("due_at"),
                        confidence = Confidence.of(raw.optString("confidence")),
                        rawSpan = raw.optStringOrNull("raw_span"),
                    )
                )
            }
        }

        return ParseResult(
            transcript = root.optString("transcript"),
            items = items,
            degraded = meta.optStringOrNull("degraded"),
            asrMs = meta.optInt("asr_ms"),
            llmMs = meta.optInt("llm_ms"),
            llmRetries = meta.optInt("llm_retries"),
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    companion object {
        private val AUDIO_TYPE = "audio/mp4".toMediaType()
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Бюджет конвейера: ASR 20 с + LLM 15 с плюс запас на дорогу.
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
