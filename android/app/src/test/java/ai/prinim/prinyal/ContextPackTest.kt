package ai.prinim.prinyal

import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteEntity
import ai.prinim.prinyal.domain.ContextPack
import ai.prinim.prinyal.domain.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Контекст-пак человек уносит наружу и там за него отвечает. Поэтому здесь
 * проверяется прежде всего одно: в файле нет ничего, чего не было в корпусе.
 */
class ContextPackTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val now: Instant = Instant.parse("2026-08-18T09:00:00Z")

    private fun at(day: Int) =
        Instant.parse("2026-08-%02dT09:00:00Z".format(day)).toEpochMilli()

    private fun note(id: String, text: String, day: Int) =
        NoteEntity(id = id, createdAt = at(day), audioPath = "", transcript = text)

    private fun item(
        id: String,
        text: String,
        type: ItemType,
        state: ItemState = ItemState.PLANNED,
        who: String? = null,
    ) = ItemEntity(
        id = id,
        noteId = "n",
        type = type.wire,
        text = text,
        who = who,
        dueKind = "none",
        state = state.wire,
        confidence = "high",
    )

    private val sources = listOf(
        ContextPack.Source(
            note("n1", "созвонились с димой решили переносить релиз на октябрь", 4),
            listOf(item("i1", "перенести релиз на октябрь", ItemType.DECISION, who = "Дима")),
        ),
        ContextPack.Source(
            note("n2", "ключи от серверной лежат у охраны на первом этаже", 10),
            listOf(item("i2", "ключи от серверной у охраны", ItemType.FACT)),
        ),
        ContextPack.Source(
            note("n3", "надо дописать документацию по авторизации", 12),
            listOf(item("i3", "дописать документацию по авторизации", ItemType.DO)),
        ),
    )

    private fun pack() = ContextPack.build("Авторизация", sources, now, zone)

    @Test
    fun `все четыре секции на месте и с датами`() {
        val md = pack()
        listOf("## Решения", "## Факты и вводные", "## Открытые вопросы", "## Сырьё")
            .forEach { assertTrue("нет секции $it:\n$md", it in md) }
        assertTrue("нет даты решения", "4 августа 2026" in md)
        assertTrue("нет «с кем»", "с кем: Дима" in md)
    }

    @Test
    fun `у висящего пункта виден возраст`() {
        // «Висит с июля» и «сказано вчера» требуют разного разговора, и в
        // бумаге эту разницу видно только по возрасту.
        assertTrue("нет возраста пункта:\n${pack()}", "6 дней назад" in pack())
    }

    @Test
    fun `в паке нет ни слова сверх корпуса`() {
        // Главная проверка: каждая строка либо заголовок, либо взята из текста
        // заметок и пунктов. Пересказывать здесь некому — и это по построению.
        val corpus = (sources.flatMap { s -> s.items.map { it.text } } +
            sources.map { it.note.transcript.orEmpty() })
            .joinToString(" ")
            .lowercase()

        pack().lines()
            .filter { it.startsWith("- ") }
            .forEach { line ->
                // Отрезаем служебный хвост «— дата, N дней назад» и «с кем».
                val body = line.removePrefix("- ").substringBefore(" — ").lowercase()
                assertTrue("строка не из корпуса: $line", body in corpus)
            }
    }

    @Test
    fun `пустая тема даёт честно короткий файл`() {
        val md = ContextPack.build("Пустая", emptyList(), now, zone)
        assertTrue("появились разделы:\n$md", "##" !in md)
        assertTrue(md.length < 80)
    }

    @Test
    fun `секции без содержимого не рисуются`() {
        val onlyFacts = listOf(sources[1])
        val md = ContextPack.build("Факты", onlyFacts, now, zone)
        assertTrue("нарисован пустой раздел решений:\n$md", "## Решения" !in md)
        assertTrue("## Факты и вводные" in md)
    }

    @Test
    fun `имя файла годится для файловой системы`() {
        val name = ContextPack.fileName("Продукт: авторизация!", java.time.LocalDate.of(2026, 8, 18))
        assertEquals("продукт-авторизация-2026-08-18.md", name)
    }

    @Test
    fun `голосовая команда узнаётся, а обычная запись — нет`() {
        // Тема остаётся в том падеже, в каком прозвучала: склонять русские
        // существительные нечем, а выдуманный именительный был бы догадкой.
        // Заголовок пака берётся из имени раздела, когда он нашёлся, — там
        // падеж правильный по определению.
        assertEquals("Авторизации", VoiceCommand.packOf("собери контекст по авторизации")?.topic)
        assertEquals("Дачу", VoiceCommand.packOf("Подготовь мне выжимку про дачу.")?.topic)

        // Детект узкий намеренно: широкий съел бы настоящую заметку, и человек
        // потерял бы сказанное. Ошибка в эту сторону дороже несрабатывания.
        assertNull(VoiceCommand.packOf("собери рюкзак на дачу"))
        assertNull(VoiceCommand.packOf("надо собрать контекст, но сначала позвонить"))
        assertNull(VoiceCommand.packOf("купить молока и хлеба"))
    }
}
