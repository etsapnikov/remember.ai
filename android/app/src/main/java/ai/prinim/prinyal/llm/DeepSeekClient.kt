package ai.prinim.prinyal.llm

import ai.prinim.prinyal.data.NoteKind
import ai.prinim.prinyal.domain.InterviewPolicy
import ai.prinim.prinyal.domain.LinkValidator
import ai.prinim.prinyal.domain.Markdown
import ai.prinim.prinyal.domain.StuckPolicy
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
/**
 * Куда уходит запрос разбора.
 *
 * Шов ради тестов кор-лупа (Р-15.16): в CI ответы модели берутся с плёнки, без
 * сети и без недетерминизма. Без этого шва каждый прогон стоил бы денег и мог
 * упасть от настроения модели — то есть тесты проверяли бы погоду, а не код.
 */
fun interface LlmTransport {
    /** @return код ответа и тело */
    fun send(payload: String): Pair<Int, String>
}

class DeepSeekClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val retries: Int = 2,
    private val client: OkHttpClient = defaultClient(),
    private val transport: LlmTransport? = null,
) {

    fun parse(
        transcript: String,
        now: LocalDateTime,
        zone: ZoneId,
        topics: List<String> = emptyList(),
        glossary: List<String> = emptyList(),
        people: List<String> = emptyList(),
        existing: List<Pair<String, String>> = emptyList(),
        /** Прежние записи, среди которых модель ищет связь (Р-15.11). */
        candidates: List<Pair<String, String>> = emptyList(),
        /**
         * Думать ли модели.
         *
         * По умолчанию **нет**: замер на всех тринадцати фикстурах показал
         * медиану 3,4 с без рассуждений против 14,7 с с ними — вчетверо
         * быстрее, — при том что ожидания сходятся одинаково везде, кроме
         * одного случая (`docs/eval-reasoning.md`). Человек ждал минуту за
         * единственный признак.
         */
        thinking: Boolean = false,
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
                call(transcript, now, topics, glossary, people, existing, candidates, thinking)
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

            val kind = NoteKind.of(root.optString("note_kind").takeIf { it.isNotBlank() })

            if (validated.items.isEmpty()) {
                // Вопрос или факт законно не рождает дел: «правда ли, что капли
                // не дольше пяти дней» — это не задача. Если модель назвала вид
                // записи, разбор состоялся, и звать это деградацией нельзя —
                // иначе карточка вопроса выглядит сломанной.
                if (kind != null) {
                    return IngestOutcome.Ok(
                        ParseResult(
                            transcript = transcript,
                            items = emptyList(),
                            noteKind = kind,
                            topic = ItemValidator.topicOf(root),
                            entities = ItemValidator.entitiesOf(root),
                            bodyMd = Markdown.sanitize(ItemValidator.stringOrNull(root, "body_md"))
                                .ifBlank { null },
                            degraded = null,
                            asrMs = 0,
                            llmMs = 0,
                            llmRetries = attempt,
                        )
                    )
                }
                // Вида записи нет и пунктов нет — вот это уже провал разбора.
                return degraded(transcript, "llm_empty", attempt)
            }

            return IngestOutcome.Ok(
                ParseResult(
                    transcript = transcript,
                    items = validated.items,
                    noteKind = kind,
                    topic = ItemValidator.topicOf(root),
                    entities = ItemValidator.entitiesOf(root),
                    // Санитайзер стоит здесь, а не у рендера: в базу не должно
                    // попадать то, что мы не готовы показать.
                    bodyMd = Markdown.sanitize(ItemValidator.stringOrNull(root, "body_md"))
                        .ifBlank { null },
                    noteDueAt = ItemValidator.parseNoteDue(root, now, zone),
                    links = LinkValidator.validate(
                        root.optJSONArray("links"),
                        candidates.map { it.first }.toSet(),
                        selfId = "",
                    ),
                    second = secondOf(root, transcript, now, zone),
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

    /**
     * Вопрос по идее (Р-15.14).
     *
     * Отдельный вызов, не расширение разбора: ответ здесь — строка, а не json,
     * и деградации у него нет. Не получилось — молчим; пустой или негодный
     * вопрос человеку хуже, чем отсутствие вопроса.
     *
     * @return вопрос, или null — спрашивать нечего
     */
    fun interview(idea: String, asked: List<String>): String? {
        if (apiKey.isBlank()) return null
        val user = buildString {
            append("Запись:\n").append(idea).append('\n')
            if (asked.isNotEmpty()) {
                append("\nУже спрашивали:\n")
                asked.forEach { append("  ").append(it).append('\n') }
            }
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", Prompt.INTERVIEW))
                put(JSONObject().put("role", "user").put("content", user))
            })
            put("temperature", 0.4)
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
        }
        val response = runCatching { post(payload) }.getOrNull() ?: return null
        if (response.code != 200) return null
        val text = response.body
            ?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            .orEmpty().trim().trim('"', '«', '»')
        val question = text.takeIf { it.isNotBlank() } ?: return null
        return question.takeIf { InterviewPolicy.accepts(it, idea, asked) }
    }

    /**
     * Уменьшить или переформулировать застрявший пункт (Р-15.8).
     *
     * Негодный ответ — это null, а не «что-нибудь»: подменить слова человека
     * отговоркой вроде «начни с малого» хуже, чем не сделать ничего. Проверку
     * ведёт [StuckPolicy], а не промпт: обещаниям модели верить нельзя.
     */
    fun rework(text: String, way: StuckPolicy.Way): String? {
        if (apiKey.isBlank()) return null
        val system = when (way) {
            StuckPolicy.Way.SHRINK -> Prompt.SHRINK
            StuckPolicy.Way.REPHRASE -> Prompt.REPHRASE
            StuckPolicy.Way.BURY -> return null
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", "Дело: $text"))
            })
            put("temperature", 0.3)
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
        }
        val response = runCatching { post(payload) }.getOrNull() ?: return null
        if (response.code != 200) return null
        val fresh = response.body
            ?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            .orEmpty().trim().trim('"', '«', '»')

        if (fresh.isBlank() || fresh.equals(text, ignoreCase = true)) return null
        return when (way) {
            StuckPolicy.Way.SHRINK -> fresh.takeIf { StuckPolicy.isRealStep(it, text) }
            // Пересказ не обязан быть короче, но отговоркой быть не вправе.
            StuckPolicy.Way.REPHRASE -> fresh.takeIf {
                StuckPolicy.isRealStep(it, text + " ".repeat(text.length))
            }
            StuckPolicy.Way.BURY -> null
        }
    }

    private class Response(val code: Int, val body: JSONObject?)

    /** Один запрос к модели: плёнка в тестах, сеть в жизни. */
    private fun post(payload: JSONObject): Response {
        transport?.let { tape ->
            val (code, text) = tape.send(payload.toString())
            return Response(code, runCatching { JSONObject(text) }.getOrNull())
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { http ->
            val text = http.body?.string().orEmpty()
            return Response(http.code, runCatching { JSONObject(text) }.getOrNull())
        }
    }

    private fun call(
        transcript: String,
        now: LocalDateTime,
        topics: List<String>,
        glossary: List<String>,
        people: List<String>,
        existing: List<Pair<String, String>>,
        candidates: List<Pair<String, String>>,
        thinking: Boolean,
    ): Response {
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", Prompt.SYSTEM))
                put(
                    JSONObject().put("role", "user")
                        .put(
                            "content",
                            Prompt.user(
                                transcript, now, topics, glossary, people, existing, candidates,
                            ),
                        )
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
            // Ключ именно такой. Первый замер выключал рассуждения через
            // `reasoning: {max_tokens: 0}` — API молча его проигнорировал,
            // модель думала в обоих прогонах, и сравнение мерило шум. Ложный
            // вывод («рассуждения ничего не стоят») продержался ровно до
            // проверки числа токенов рассуждений в ответе.
            if (!thinking) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            // Рассуждения включены. Прогон корпуса 16.08 (18 записей): число
            // пунктов почти не меняется, но качество разбора заметно лучше —
            // «камера опафаиндекс шесть» становится «узнать, на каком месте
            // камера OPPO Find X6», событие с датой получает тип `date`, а не
            // `fact`, и «завтра вечером» уходит в окно, а не в мнимое точное
            // время. Расхождение в пользу рассуждений — на 10 записях из 18.
            // Цена: медиана 16 с против 2–4. Разбор фоновый, квитанция уже
            // показана — этих секунд человек не ждёт.
        }

        transport?.let { tape ->
            val (code, text) = tape.send(payload.toString())
            return Response(code, runCatching { JSONObject(text) }.getOrNull())
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
    /**
     * Вторая половина разделённой записи.
     *
     * Разбирается тем же валидатором, что и первая: половина, прошедшая мягче
     * основной, — это дыра, через которую в базу попадёт то, что мы в основной
     * ветке отбраковываем.
     *
     * Пустая вторая половина — не разделение: заметка без единого пункта
     * никому не нужна, и заводить её значит плодить мусор.
     */
    private fun secondOf(
        root: JSONObject,
        transcript: String,
        now: LocalDateTime,
        zone: ZoneId,
    ): ParseResult? {
        if (root.isNull("second")) return null
        val node = root.optJSONObject("second") ?: return null
        val items = ItemValidator.validate(itemsOf(node), transcript, now, zone).items
        if (items.isEmpty()) return null

        return ParseResult(
            transcript = transcript,
            items = items,
            noteKind = NoteKind.of(node.optString("note_kind").takeIf { it.isNotBlank() }),
            topic = ItemValidator.topicOf(node),
            entities = ItemValidator.entitiesOf(node),
            bodyMd = Markdown.sanitize(ItemValidator.stringOrNull(node, "body_md"))
                .ifBlank { null },
            degraded = null,
            asrMs = 0,
            llmMs = 0,
            llmRetries = 0,
        )
    }

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

        /**
         * Бюджет на рассуждения и ответ вместе.
         *
         * Было 8192 — и на записи-идее ответ упирался в потолок: рассуждения
         * съедали ~6700, а тело «Собрано» не помещалось в остаток, приходил
         * `finish_reason: length` с пустым содержимым. Замер 16.08: тот же
         * запрос при 16384 отвечает за 58 с и тратит 7315.
         */
        const val MAX_TOKENS = 16_384
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
