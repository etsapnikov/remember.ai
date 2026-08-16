package ai.prinim.prinyal

import ai.prinim.prinyal.data.ReplacementEntity
import ai.prinim.prinyal.domain.Replacements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Словарь автозамен. Проверяем ровно те случаи, из-за которых наивная замена
 * подстроки испортила бы транскрипт.
 */
class ReplacementsTest {

    private var seq = 0

    private fun rule(from: String, to: String) = ReplacementEntity(
        id = "r${seq++}",
        fromPhrase = from,
        fromNorm = Replacements.norm(from),
        toPhrase = to,
        createdAt = 0,
    )

    @Test
    fun `правило чинит слово и считает срабатывания`() {
        val out = Replacements.apply(
            "гига ам распознал, потом гига ам сломался",
            listOf(rule("гига ам", "GigaAM")),
        )
        assertEquals("GigaAM распознал, потом GigaAM сломался", out.text)
        assertEquals(2, out.hits.values.single())
    }

    @Test
    fun `замена не лезет внутрь других слов`() {
        // Ради этого и нужны границы: иначе «юля» испортила бы «юлия».
        val out = Replacements.apply("юлия и юля", listOf(rule("юля", "Юля")))
        assertEquals("юлия и Юля", out.text)
    }

    @Test
    fun `длинное правило срабатывает раньше короткого`() {
        // Если бы сначала применилось «лору», фраза целиком уже не совпала бы.
        val out = Replacements.apply(
            "соник лору записать",
            listOf(rule("лору", "к лору"), rule("соник лору", "Соню к лору")),
        )
        assertEquals("Соню к лору записать", out.text)
    }

    @Test
    fun `заглавная буква исходника сохраняется`() {
        val out = Replacements.apply("Гига ам молчит", listOf(rule("гига ам", "gigaAM")))
        assertEquals("GigaAM молчит", out.text)
    }

    @Test
    fun `пустой словарь и пустой текст ничего не ломают`() {
        assertEquals("текст", Replacements.apply("текст", emptyList()).text)
        assertEquals("", Replacements.apply("", listOf(rule("а", "б"))).text)
    }

    @Test
    fun `фраза длиннее трёх слов правилом не становится`() {
        // Словарь чинит слух на именах, а не переписывает предложения.
        assertTrue(Replacements.fits("соник лору записать"))
        assertFalse(Replacements.fits("надо соник лору записать"))
        assertFalse(Replacements.fits("   "))
    }

    @Test
    fun `нормализация схлопывает регистр и пробелы`() {
        assertEquals("гига ам", Replacements.norm("  Гига   Ам "))
    }

    @Test
    fun `глоссарий отдаёт пары, а не инструкцию`() {
        val glossary = Replacements.glossary(listOf(rule("гига ам", "GigaAM")))
        assertEquals(listOf("гига ам → GigaAM"), glossary)
    }
}
