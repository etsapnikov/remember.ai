package ai.prinim.prinyal.llm

import ai.prinim.prinyal.data.NoteKind
import ai.prinim.prinyal.domain.Markdown
import ai.prinim.prinyal.net.IngestOutcome
import ai.prinim.prinyal.net.ParseResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Разбор комка прямо с телефона (PRD §2.2, стадия LLM).
 *
 * Осознанное отступление от PRD §2 п.3 и §7: там ключ живёт только на бэкенде,
 * здесь он лежит в APK. Решение владельца ради версии, которая работает без
 * сервера. Цена: у любого, кто получит APK, будет и ключ, — поэтому ключ должен
 * быть отдельным и с лимитом расходов.
 *
 * Логика ретраев и деградаций повторяет серверную (`app/llm.py`): один вызов,
 * json_object, до двух ретраев на пустой content. Рассуждения модели включены —
 * см. комментарий у `max_tokens`.
 */
class DeepSeekClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val retries: Int = 2,
    private val client: OkHttpClient = defaultClient(),
) {

    fun parse(
        transcript: String,
        now: LocalDateTime,
        zone: ZoneId,
        topics: List<String> = emptyList(),
        glossary: List<String> = emptyList(),
    ): IngestOutcome {
        if (apiKey.isBlank()) {
            return degraded(transcript, "llm_disabled", 0)
        }

        var lastReason = "llm_error"
        // Сети нет вовсе — это не деградация разбора, а «придёт позже» (§6):
        // запись должна остаться в очереди, а не получить fallback-план навсегда.
        var reachedServer = false

        for (attempt in 0..retries) {
            val response = try {
                call(transcript, now, topics, glossary)
            } catch (error: Exception) {
                when {
                    error is java.net.UnknownHostException ||
                        error is java.net.ConnectException ||
                        error is java.net.NoRouteToHostException -> Unit
                    error is IOException && error.isTimeout() -> {
                        reachedServer = true
                        lastReason = "llm_timeout"
                    }
                    else -> {
                        reachedServer = true
                        lastReason = "llm_error"
                    }
                }
                continue
            }
            reachedServer = true

            when {
                response.code == 402 -> return degraded(transcript, "llm_no_balance", attempt)
                response.code == 401 -> return degraded(transcript, "llm_error", attempt)
                response.code == 429 || response.code >= 500 -> {
                    lastReason = "llm_error"
                    continue
                }
                response.code >= 400 -> return degraded(transcript, "llm_error", attempt)
            }

            val body = response.body
            if (body == null) {
                lastReason = "llm_error"
                continue
            }
            val choice = body.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("message")?.optString("content").orEmpty().trim()
            val finish = choice?.optString("finish_reason").orEmpty()

            if (content.isEmpty()) {
                lastReason = "llm_empty"
                // Модель упёрлась в потолок токенов — повтор даст ровно то же самое,
                // только заставит человека ждать ещё два круга.
                if (finish == "length") return degraded(transcript, "llm_empty", attempt)
                continue
            }

            val root = rootOf(content)
            if (root == null) {
                lastReason = "llm_error"
                continue
            }
            val items = itemsOf(root)
            val validated = ItemValidator.validate(items, transcript, now, zone)

            if (validated.items.isEmpty()) {
                // Модель ответила, но пунктов не нашла — запись всё равно не теряем.
                return degraded(transcript, "llm_empty", attempt)
            }

            return IngestOutcome.Ok(
                ParseResult(
                    transcript = transcript,
                    items = validated.items,
                    noteKind = NoteKind.of(root.optString("note_kind").takeIf { it.isNotBlank() }),
                    topic = ItemValidator.topicOf(root),
                    entities = ItemValidator.entitiesOf(root),
                    // Санитайзер стоит здесь, а не у рендера: в базу не должно
                    // попадать то, что мы не готовы показать.
                    bodyMd = Markdown.sanitize(root.optString("body_md")).ifBlank { null },
                    degraded = null,
                    asrMs = 0,
                    llmMs = 0,
                    llmRetries = attempt,
                )
            )
        }

        // До DeepSeek не достучались ни разу — ждём сеть, план не выдумываем.
        if (!reachedServer) return IngestOutcome.Retryable("no_server")

        return degraded(transcript, lastReason, retries)
    }

    private class Response(val code: Int, val body: JSONObject?)

    private fun call(
        transcript: String,
        now: LocalDateTime,
        topics: List<String>,
        glossary: List<String>,
    ): Response {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", Prompt.SYSTEM))
                put(
                    JSONObject().put("role", "user")
                        .put("content", Prompt.user(transcript, now, topics, glossary))
                )
            })
            put("temperature", 0.1)
            // Бюджет считается вместе с рассуждениями: модель тратит на них
            // 6500–7900 токенов, и при прежних 2048 ответа не оставалось вовсе —
            // приходил пустой content с `finish_reason: length`. Это была наша
            // ошибка в бюджете, а не поведение DeepSeek.
            put("max_tokens", MAX_TOKENS)
            put("response_format", JSONObject().put("type", "json_object"))
            put("stream", false)
            // Рассуждения включены. Прогон корпуса 16.08 (18 записей): число
            // пунктов почти не меняется, но качество разбора заметно лучше —
            // «камера опафаиндекс шесть» становится «узнать, на каком месте
            // камера OPPO Find X6», событие с датой получает тип `date`, а не
            // `fact`, и «завтра вечером» уходит в окно, а не в мнимое точное
            // время. Расхождение в пользу рассуждений — на 10 записях из 18.
            // Цена: медиана 16 с против 2–4. Разбор фоновый, квитанция уже
            // показана — этих секунд человек не ждёт.
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { http ->
            val text = http.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            return Response(http.code, json)
        }
    }

    /**
     * Модель обязана вернуть json_object, но обёртка в ```json``` встречается и у
     * послушных провайдеров — снимаем её, прежде чем сдаваться.
     */
    private fun rootOf(content: String): JSONObject? {
        var text = content.trim()
        if (text.startsWith("```")) {
            text = text.trim('`').removePrefix("json").trim()
        }
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun itemsOf(root: JSONObject): List<JSONObject> {
        val array = root.optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun degraded(transcript: String, reason: String, retries: Int) = IngestOutcome.Ok(
        ParseResult(
            transcript = transcript,
            items = ItemValidator.fallback(transcript),
            degraded = reason,
            asrMs = 0,
            llmMs = 0,
            llmRetries = retries,
        )
    )

    private fun IOException.isTimeout(): Boolean =
        this is java.net.SocketTimeoutException ||
            message?.contains("timeout", ignoreCase = true) == true

    companion object {
        const val DEFAULT_MODEL = "deepseek-v4-flash"

        /** Бюджет на рассуждения и ответ вместе: рассуждений бывает до 7900. */
        const val MAX_TOKENS = 8192
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Рассуждения тратят время: медиана 16 с, длинная запись — до 47.
            // Прежних 30 секунд не хватало бы ровно на самых сложных записях,
            // то есть там, где рассуждения и нужны.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
