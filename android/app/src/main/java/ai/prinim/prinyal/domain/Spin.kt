package ai.prinim.prinyal.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * «Покрутить идею» (спека 1.0 от 24.08): один вызов — один вопрос.
 *
 * Механика **stateless**: в промпт каждый раз кладётся вся заметка со всеми
 * парами вопрос-ответ, история заданных вопросов и свежий контекст. Никакого
 * состояния между вызовами, кроме самой заметки, — так дешевле и надёжнее, чем
 * вести диалоговую сессию.
 *
 * Здесь живут вход, разбор ответа и **валидация**. Валидация не формальность:
 * обязанность задекларировать использованный факт — то, что заставляет модель
 * работать с контекстом, а не игнорировать его. Заодно это телеметрия: по логу
 * видно, какая доля вопросов вышла контекстной.
 */
object Spin {

    data class Exchange(val question: String, val answer: String)

    data class Fact(
        val id: String,
        val text: String,
        /** `profile` — что известно о человеке; `archive` — прошлая заметка. */
        val source: String,
    )

    data class Input(
        val raw: String,
        val exchanges: List<Exchange>,
        val askedQuestions: List<String>,
        val skippedCount: Int,
        val facts: List<Fact>,
        val now: String,
    )

    /** Что вернула модель после проверки: вопрос либо резюме. */
    sealed interface Result {
        data class Ask(
            val text: String,
            val slot: String,
            val factId: String?,
            val factRole: String,
            val anchor: String?,
        ) : Result

        data class Summary(
            val oneLiner: String,
            val nextStep: String,
            val filled: String,
        ) : Result
    }

    fun buildUser(input: Input): String {
        val note = JSONObject().apply {
            put("raw", input.raw)
            put(
                "exchanges",
                JSONArray().apply {
                    input.exchanges.forEach {
                        put(JSONObject().put("q", it.question).put("a", it.answer))
                    }
                },
            )
        }
        return JSONObject().apply {
            put("note", note)
            put("asked_questions", JSONArray(input.askedQuestions))
            put("skipped_count", input.skippedCount)
            put(
                "context_facts",
                JSONArray().apply {
                    input.facts.forEach {
                        put(
                            JSONObject()
                                .put("id", it.id)
                                .put("text", it.text)
                                .put("source", it.source)
                        )
                    }
                },
            )
            put("now", input.now)
        }.toString()
    }

    /**
     * Разбор с проверкой (спека §5). Не прошло — null, и вызывающий ретраит.
     *
     * Пункты про факт (3, 5, 6) ключевые: без них модель начинает декларировать
     * работу с контекстом, не делая её.
     */
    fun parse(raw: String, input: Input): Result? {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        if (root.optString("action") == "summarize") {
            val summary = root.optJSONObject("summary") ?: return null
            val one = summary.optString("one_liner").trim()
            if (one.isEmpty()) return null
            return Result.Summary(
                oneLiner = one,
                nextStep = summary.optString("next_step").trim(),
                filled = summary.optString("filled").trim(),
            )
        }

        val question = root.optJSONObject("question") ?: return null
        val text = question.optString("text").trim()
        // 2. Пустой, длинный, без знака вопроса или с двумя — брак.
        if (text.isEmpty() || text.length > MAX_CHARS) return null
        if (!text.contains('?')) return null
        if (text.count { it == '?' } > 1) return null

        // 4. Повтор уже заданного — самое частое, что портит луп.
        if (input.askedQuestions.any { similar(it, text) }) return null

        val role = question.optString("fact_role").ifBlank { "none" }
        val factId = question.optString("context_fact_id").takeIf {
            !question.isNull("context_fact_id") && it.isNotBlank() && it != "null"
        }
        // 3. Сослался на факт, которого мы не давали.
        if (factId != null && input.facts.none { it.id == factId }) return null
        // 5. Объявил роль факта, а факта не назвал.
        if (role != "none" && factId == null) return null

        val anchor = question.optString("anchor_quote").takeIf {
            !question.isNull("anchor_quote") && it.isNotBlank() && it != "null"
        }
        // 6. Процитировал то, чего человек не говорил.
        if (anchor != null && !input.raw.lowercase().contains(anchor.lowercase()) &&
            input.exchanges.none { it.answer.lowercase().contains(anchor.lowercase()) }
        ) {
            return null
        }

        return Result.Ask(
            text = text,
            slot = question.optString("slot").ifBlank { "outcome" },
            factId = factId,
            factRole = role,
            anchor = anchor,
        )
    }

    /**
     * Похожесть по триграммам (спека §5, п. 4): порог 0.85.
     *
     * Дословный повтор ловится и сравнением строк, а перефразированный — нет,
     * и именно он бесит: человек видит тот же вопрос другими словами.
     */
    fun similar(a: String, b: String): Boolean {
        val x = trigrams(a)
        val y = trigrams(b)
        if (x.isEmpty() || y.isEmpty()) return a.equals(b, ignoreCase = true)
        val common = x.intersect(y).size.toDouble()
        return common / maxOf(x.size, y.size) >= 0.85
    }

    private fun trigrams(text: String): Set<String> {
        val clean = text.lowercase().replace(Regex("[^а-яёa-z0-9 ]"), "").trim()
        if (clean.length < 3) return emptySet()
        return (0..clean.length - 3).map { clean.substring(it, it + 3) }.toSet()
    }

    /**
     * Заготовки на случай, когда модель дважды не смогла (спека §5).
     *
     * Луп не ломается никогда: лучше общий вопрос, чем оборванный разговор.
     * Заготовки нарочно скучные — они не притворяются умными.
     */
    fun fallback(slot: String): String = when (slot) {
        "outcome" -> "Как поймёшь, что получилось хорошо?"
        "givens" -> "Что тут уже точно — сроки, деньги, люди?"
        "fork" -> "Какой выбор тут главный?"
        "risks" -> "Что может это сорвать?"
        else -> "Какой первый шаг и когда?"
    }

    /** Порядок слотов по типу заметки (спека §3, шаг 5) — для фолбэка. */
    fun firstEmptySlot(type: String, empty: Set<String>): String {
        val order = when (type) {
            "plan" -> listOf("fork", "givens", "outcome", "step")
            "task" -> listOf("step", "givens")
            "reflection" -> listOf("outcome", "fork")
            else -> listOf("outcome", "fork", "risks", "step")
        }
        return order.firstOrNull { it in empty } ?: order.first()
    }

    private const val MAX_CHARS = 120
}
