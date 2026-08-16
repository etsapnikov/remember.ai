package ai.prinim.prinyal

import ai.prinim.prinyal.llm.ItemValidator
import ai.prinim.prinyal.llm.Prompt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * Раздел приходит от модели строкой, и строка эта может быть любой. Проверяем
 * не «модель умная», а что мусор до базы не доезжает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TopicParseTest {

    private fun root(json: String) = JSONObject(json)

    @Test
    fun `обычное имя раздела проходит и получает заглавную`() {
        assertEquals("Дача", ItemValidator.topicOf(root("""{"topic":"дача"}""")))
        assertEquals("Дом", ItemValidator.topicOf(root("""{"topic":" Дом "}""")))
    }

    @Test
    fun `отсутствие раздела — законный ответ, а не ошибка`() {
        // Пустой раздел дешевле неверного: заметка просто ждёт руки.
        assertNull(ItemValidator.topicOf(root("""{"topic":null}""")))
        assertNull(ItemValidator.topicOf(root("""{"topic":""}""")))
        assertNull(ItemValidator.topicOf(root("""{}""")))
        assertNull(ItemValidator.topicOf(root("""{"topic":"null"}""")))
    }

    @Test
    fun `пересказ записи вместо раздела отбрасывается`() {
        // Три слова и больше — это уже не раздел, а описание.
        assertNull(ItemValidator.topicOf(root("""{"topic":"дела по дому на выходные"}""")))
    }

    @Test
    fun `кавычки вокруг имени снимаются`() {
        assertEquals("Работа", ItemValidator.topicOf(root("""{"topic":"«Работа»"}""")))
    }

    @Test
    fun `имена людей вычищаются от пустот и дублей`() {
        val entities = ItemValidator.entitiesOf(
            root("""{"entities":["Соня","","null"," соня ","Юля"]}""")
        )
        assertEquals(listOf("Соня", "Юля"), entities)
    }

    @Test
    fun `нет поля entities — пустой список, а не падение`() {
        assertEquals(emptyList<String>(), ItemValidator.entitiesOf(root("""{}""")))
    }

    @Test
    fun `существующие разделы уходят в промпт списком`() {
        val now = LocalDateTime.of(2026, 8, 16, 21, 0)
        val withTopics = Prompt.user("купить капли", now, listOf("Дом", "Работа"))
        assertTrue(withTopics.contains("Дом, Работа"))

        // Разделов ещё нет — модель должна понимать, что заводит первый.
        val without = Prompt.user("купить капли", now, emptyList())
        assertTrue(without.contains("Разделов пока нет"))
    }

    @Test
    fun `промпт версии 3 объявляет новые поля`() {
        assertEquals("3", Prompt.VERSION)
        assertTrue(Prompt.SYSTEM.contains("note_kind"))
        assertTrue(Prompt.SYSTEM.contains("topic"))
        assertTrue(Prompt.SYSTEM.contains("entities"))
    }
}
