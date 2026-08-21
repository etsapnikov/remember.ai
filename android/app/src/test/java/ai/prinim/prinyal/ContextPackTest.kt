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
    fun `запись за записью — что понято и что сказано`() {
        val md = pack()
        // Сводных разделов по типу пункта больше нет: они собирали корпус
        // поперёк времени, и связь между строками терялась.
        listOf("## Решения", "## Факты и вводные", "## Открытые вопросы", "## Сырьё")
            .forEach { assertTrue("вернулась сводная секция $it:\n$md", it !in md) }

        // Заголовок записи — дата и время, дальше понятое, дальше сказанное.
        assertTrue("нет заголовка записи:\n$md", "## 4 августа 2026 · " in md)
        assertTrue("нет пункта записи:\n$md", "- перенести релиз на октябрь" in md)
        assertTrue("нет «с кем»:\n$md", "· Дима" in md)
        assertTrue("нет расшифровки:\n$md", "> созвонились с димой" in md)
    }

    @Test
    fun `саммари заметки идёт вместо списка пунктов, когда оно есть`() {
        // «Как сейчас генерится»: если модель написала связный пересказ, в пак
        // идёт он, а пункты не дублируют его же другими словами.
        val withBody = listOf(
            ContextPack.Source(
                note("n4", "речь про замысел", 12).copy(bodyMd = "## Замысел\n\nЧто задумано."),
                listOf(item("i4", "сделать замысел", ItemType.DO)),
            )
        )
        val md = ContextPack.build("Замысел", withBody, now, zone)
        assertTrue("нет саммари:\n$md", "## Замысел" in md && "Что задумано." in md)
        assertTrue("пункты продублировали саммари:\n$md", "- сделать замысел" !in md)
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
                // Отрезаем служебный хвост «· с кем».
                val body = line.removePrefix("- ").substringBefore(" · ").lowercase()
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
    fun `запись без речи в пак не попадает`() {
        // Пустая расшифровка — это не блок с пустой цитатой, а отсутствие
        // блока: заголовок без текста читается как потерянная запись.
        val silent = listOf(
            ContextPack.Source(note("n5", "", 12), listOf(item("i5", "дело", ItemType.DO)))
        )
        val md = ContextPack.build("Тихая", silent, now, zone)
        assertTrue("нарисован блок пустой записи:\n$md", "##" !in md)
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
