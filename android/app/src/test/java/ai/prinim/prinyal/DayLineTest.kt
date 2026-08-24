package ai.prinim.prinyal

import ai.prinim.prinyal.domain.DayLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Строка дня (Р-18.2): своими словами продукт про день не говорит никогда. */
class DayLineTest {

    private val answer = "ну самое главное досидели с игорем до сметы " +
        "три недели не могли а сегодня сели и до конца дожали наконец сдвинулось"

    @Test
    fun `сжатие из слов ответа проходит`() {
        assertEquals(
            "досидели с игорем до сметы наконец сдвинулось",
            DayLine.of(answer, "досидели с игорем до сметы наконец сдвинулось"),
        )
    }

    @Test
    fun `чужое слово роняет сжатие в начало ответа`() {
        // Модель «пересказала»: слово «продуктивно» человек не говорил.
        val line = DayLine.of(answer, "продуктивно поработали над сметой")
        assertTrue("выдумка прошла в строку", "продуктивно" !in line)
        assertTrue("фолбэк не из начала ответа", line.startsWith("ну самое главное"))
    }

    @Test
    fun `слишком длинное предложение модели отбрасывается`() {
        val line = DayLine.of(answer, answer + " " + answer)
        assertTrue(line.length <= DayLine.CAP)
    }

    @Test
    fun `фолбэк режет по границе слова и без многоточия`() {
        val long = (1..40).joinToString(" ") { "слово$it" }
        val line = DayLine.opening(long)
        assertTrue(line.length <= DayLine.CAP)
        assertTrue("обрезано посреди слова", long.startsWith(line))
        assertTrue("многоточие пролезло", !line.endsWith("…"))
    }

    @Test
    fun `ничего — валидный ответ`() {
        assertEquals("ничего", DayLine.of("ничего", "ничего"))
    }
}
