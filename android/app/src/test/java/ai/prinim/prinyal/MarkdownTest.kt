package ai.prinim.prinyal

import ai.prinim.prinyal.domain.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-набор для блока «Собрано».
 *
 * Текст сюда приходит от языковой модели — от стороны, которой мы не управляем.
 * Проверяем не «модель пишет красиво», а что чужой текст не может ни исполниться,
 * ни уронить карточку.
 */
class MarkdownTest {

    @Test
    fun `валидный markdown разбирается на блоки`() {
        val blocks = Markdown.parse(
            Markdown.sanitize(
                """
                ## Курс для родителей
                Четыре встречи по будням.

                ### Что проверить
                - пойдут ли по будням
                - потянут ли платно
                """.trimIndent()
            )
        )
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is Markdown.Block.Heading)
        assertEquals(2, (blocks[0] as Markdown.Block.Heading).level)
        assertTrue(blocks[3] is Markdown.Block.Bullet)
    }

    @Test
    fun `HTML не исполняется и не показывается тегами`() {
        val clean = Markdown.sanitize("Идея <script>alert(1)</script> и <b>жирный</b> текст")
        assertFalse("тег дошёл до рендера", clean.contains("<"))
        assertFalse(clean.contains("script>"))
        assertTrue(clean.contains("Идея"))
        assertTrue(clean.contains("текст"))
    }

    @Test
    fun `незакрытые маркеры деградируют в плоский текст`() {
        val spans = Markdown.spans("это **важно и не закрыто")
        assertEquals(1, spans.size)
        assertFalse(spans.single().bold)
        assertTrue(spans.single().text.contains("**важно"))
    }

    @Test
    fun `вложенные списки не ломают разбор`() {
        val blocks = Markdown.parse(
            Markdown.sanitize("- верхний\n  - вложенный\n    - глубже")
        )
        assertEquals(3, blocks.size)
        assertTrue(blocks.all { it is Markdown.Block.Bullet })
    }

    @Test
    fun `таблицы и линейки вычищаются целиком`() {
        val clean = Markdown.sanitize(
            "текст\n| столбец | второй |\n|---|---|\n| да | нет |\n---\nещё текст"
        )
        assertFalse(clean.contains("|"))
        assertTrue(clean.contains("текст"))
        assertTrue(clean.contains("ещё текст"))
    }

    @Test
    fun `ссылки теряют адрес, но не подпись`() {
        val clean = Markdown.sanitize("смотри [сюда](https://example.com/track?id=1)")
        assertEquals("смотри сюда", clean)
    }

    @Test
    fun `картинки уходят целиком`() {
        assertEquals("до после", Markdown.sanitize("до ![подпись](http://x/y.png) после"))
    }

    @Test
    fun `нумерованный список теряет маркер, но не смысл`() {
        val blocks = Markdown.parse(Markdown.sanitize("1. первое\n2. второе"))
        assertEquals(2, blocks.size)
        assertEquals("первое", (blocks[0] as Markdown.Block.Paragraph).text)
    }

    @Test
    fun `заголовки вне h2 и h3 опускаются до текста`() {
        val blocks = Markdown.parse(Markdown.sanitize("# крупный\n#### мелкий"))
        assertTrue(blocks.all { it is Markdown.Block.Paragraph })
    }

    @Test
    fun `пустота остаётся пустотой, а не падением`() {
        assertEquals("", Markdown.sanitize(null))
        assertEquals("", Markdown.sanitize("   \n\n  "))
        assertEquals(emptyList<Markdown.Block>(), Markdown.parse(""))
    }

    @Test
    fun `очень длинный текст обрезается, а не съедает память`() {
        val clean = Markdown.sanitize("а".repeat(50_000))
        assertTrue(clean.length <= 8_000)
    }

    @Test
    fun `инлайн-разметка разбирается по видам`() {
        val spans = Markdown.spans("обычный **жирный** и *курсив* и `код`")
        assertTrue(spans.any { it.bold && it.text == "жирный" })
        assertTrue(spans.any { it.italic && it.text == "курсив" })
        assertTrue(spans.any { it.code && it.text == "код" })
    }
}
