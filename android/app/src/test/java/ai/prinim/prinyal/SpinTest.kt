package ai.prinim.prinyal

import ai.prinim.prinyal.domain.Spin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Валидация «Покрутить идею» (спека §5): модели не доверяем.
 *
 * Правила про факт — не формальность. Обязанность задекларировать
 * использованный факт заставляет модель работать с контекстом, а не
 * притворяться, что работает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpinTest {

    private val input = Spin.Input(
        raw = "надо спланировать новогодние каникулы подешевле",
        exchanges = emptyList(),
        askedQuestions = listOf("Куда хочешь поехать?"),
        skippedCount = 0,
        facts = listOf(Spin.Fact("f1", "в октябре ездил в Стамбул", "archive")),
        now = "2026-08-24T21:40+03:00",
    )

    private fun ask(
        text: String = "Какой потолок бюджета?",
        factId: String? = null,
        role: String = "none",
        anchor: String? = null,
    ) = """
        {"action":"ask","question":{"text":"$text","slot":"givens",
         "context_fact_id":${factId?.let { "\"$it\"" } ?: "null"},
         "fact_role":"$role",
         "anchor_quote":${anchor?.let { "\"$it\"" } ?: "null"}},"summary":null}
    """.trimIndent()

    @Test
    fun `годный вопрос проходит`() {
        val result = Spin.parse(ask(), input) as Spin.Result.Ask
        assertEquals("Какой потолок бюджета?", result.text)
    }

    @Test
    fun `бракуем пустой, длинный и без вопроса`() {
        assertNull(Spin.parse(ask(text = ""), input))
        assertNull(Spin.parse(ask(text = "Куда поехать"), input))
        assertNull(Spin.parse(ask(text = "А" + "б".repeat(130) + "?"), input))
    }

    @Test
    fun `два вопросительных знака — это два вопроса`() {
        assertNull(Spin.parse(ask(text = "Куда поехать? И на сколько?"), input))
    }

    @Test
    fun `перефразированный повтор не проходит`() {
        // Дословный повтор ловится и сравнением строк. Бесит именно
        // перефразированный: человек видит тот же вопрос другими словами.
        assertNull(Spin.parse(ask(text = "Куда хочешь поехать?"), input))
        assertNull(Spin.parse(ask(text = "Куда хочешь поехать??"), input))
    }

    @Test
    fun `ссылка на факт, которого мы не давали`() {
        assertNull(Spin.parse(ask(factId = "f9", role = "anchor"), input))
    }

    @Test
    fun `роль факта объявлена, а факт не назван`() {
        // Декларация без факта — способ отчитаться о работе с контекстом,
        // не сделав её.
        assertNull(Spin.parse(ask(role = "contradiction"), input))
    }

    @Test
    fun `цитата, которой человек не говорил`() {
        assertNull(Spin.parse(ask(anchor = "премиум-класс"), input))
        val ok = Spin.parse(ask(anchor = "подешевле"), input) as Spin.Result.Ask
        assertEquals("подешевле", ok.anchor)
    }

    @Test
    fun `резюме разбирается и требует сути`() {
        val summary = Spin.parse(
            """{"action":"summarize","question":null,
                "summary":{"one_liner":"Уехать вдвоём","next_step":"Завтра билеты","filled":"Бюджет"}}""",
            input,
        ) as Spin.Result.Summary
        assertEquals("Уехать вдвоём", summary.oneLiner)

        assertNull(
            Spin.parse(
                """{"action":"summarize","question":null,
                    "summary":{"one_liner":"","next_step":"","filled":""}}""",
                input,
            )
        )
    }

    @Test
    fun `мусор вместо json не роняет разбор`() {
        assertNull(Spin.parse("", input))
        assertNull(Spin.parse("извини, не могу", input))
    }

    @Test
    fun `заготовка есть на каждый слот`() {
        listOf("outcome", "givens", "fork", "risks", "step").forEach { slot ->
            assertTrue(Spin.fallback(slot).endsWith("?"))
        }
        // Порядок слотов зависит от типа заметки (спека §3, шаг 5).
        assertEquals("fork", Spin.firstEmptySlot("plan", setOf("fork", "risks")))
        assertEquals("step", Spin.firstEmptySlot("task", setOf("step", "risks")))
        assertEquals("outcome", Spin.firstEmptySlot("hypothesis", setOf("outcome", "step")))
    }
}
