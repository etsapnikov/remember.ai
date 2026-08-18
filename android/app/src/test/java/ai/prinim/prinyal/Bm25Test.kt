package ai.prinim.prinyal

import ai.prinim.prinyal.domain.Bm25
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Топливо для авто-линковки. Проверяем не «релевантность вообще», а те свойства,
 * на которые опирается потребитель: редкое слово решает, частое — нет, чужого в
 * выдаче не бывает.
 */
class Bm25Test {

    private val corpus = Bm25(
        listOf(
            Bm25.Doc("markdown", "и также надо бы маркдаун поддержать когда заметка парсится"),
            Bm25.Doc("tests", "нужно сделать тесты на разные типы заметок со списками с маркдауном"),
            Bm25.Doc("notify", "нужно придумать механику уведомлений зачем они нужны"),
            Bm25.Doc("bug", "в трее нету значка уведомления надо исправить баг"),
            Bm25.Doc("milk", "напомни заказать кефир в самокате сегодня вечером"),
        )
    )

    @Test
    fun `редкое слово связывает, даже когда остальной текст разный`() {
        val hits = corpus.similarTo("markdown")
        assertEquals("tests", hits.first().id)
    }

    @Test
    fun `падежи не мешают`() {
        // «маркдаун» и «маркдауном», «уведомлений» и «уведомления» — одно слово
        // для человека, и должны быть одним для поиска.
        assertEquals("bug", corpus.similarTo("notify").first().id)
    }

    @Test
    fun `сама заметка в выдачу не попадает`() {
        assertTrue(corpus.similarTo("markdown").none { it.id == "markdown" })
    }

    @Test
    fun `совсем чужая заметка кандидатов не получает`() {
        // Ни одного общего слова — и пусто. Кандидат с нулевым совпадением хуже,
        // чем его отсутствие: он занял бы место в списке из восьми.
        val alone = Bm25(listOf(Bm25.Doc("a", "лего про космос"), Bm25.Doc("b", "оплатить проезд")))
        assertTrue(alone.similarTo("a").isEmpty())
    }

    @Test
    fun `выдача не длиннее запрошенного и отсортирована`() {
        val hits = corpus.similarTo("markdown", limit = 2)
        assertTrue(hits.size <= 2)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun `пустой корпус и незнакомый id не роняют поиск`() {
        assertTrue(Bm25(emptyList()).similarTo("нет").isEmpty())
        assertTrue(corpus.similarTo("такого-нет").isEmpty())
    }

    @Test
    fun `счёт совпадает с замером спайка до четвёртого знака`() {
        // Отчёт docs/spike-1_0_2-semsearch.md посчитан питоновской реализацией.
        // Если эти две разойдутся, отчёт станет описывать не то, что работает,
        // и заметить это будет нечем. Эталон снят с того же кода, что считал
        // числа отчёта, на этом самом корпусе.
        val expected = mapOf("tests" to 1.5541, "bug" to 0.8929)
        val got = corpus.similarTo("markdown").associate { it.id to it.score }
        assertEquals(expected.keys, got.keys)
        expected.forEach { (id, want) ->
            assertEquals(id, want, got.getValue(id), 1e-4)
        }
    }

    @Test
    fun `токенизация рубит только длинные слова`() {
        // Короткое рубить нечего: «дом» и «доме» обязаны остаться разными от
        // «домофон» ровно настолько, насколько их различает пятибуквенная база.
        assertEquals(listOf("дом", "заказ", "кефир"), Bm25.tokenize("Дом, заказать кефир!"))
    }
}
