package ai.prinim.prinyal

import ai.prinim.prinyal.data.NoteEntity
import ai.prinim.prinyal.data.NoteWithItems
import ai.prinim.prinyal.domain.NoteSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поиск (Р-18.5): по словам человека, с подсвечиваемым совпадением в каждой
 * строке выдачи.
 */
class NoteSearchTest {

    private fun entry(id: String, text: String, at: Long = 1_000L) =
        NoteWithItems(
            NoteEntity(id = id, createdAt = at, audioPath = "", transcript = text),
            emptyList(),
        )

    @Test
    fun `находит по началу слова и подсвечивает слово целиком`() {
        // «дача» находит «даче», но подсвечивается «даче», а не «дач-»:
        // огрызок подсветки читается как сбой рендера.
        val results = NoteSearch.search(
            listOf(entry("n1", "надо бы забор докрасить на даче")),
            "дача",
        )
        val spans = results.single().spans
        val text = "надо бы забор докрасить на даче"
        assertEquals(listOf("даче"), spans.map { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun `совпадение в середине чужого слова не считается`() {
        // «дача» не должна находить «передачу»: совпадение в середине чужого
        // слова человека путает, а не помогает.
        val results = NoteSearch.search(
            listOf(entry("n1", "посмотрел передачу про горы")),
            "дача",
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun `строка без буквального совпадения в выдачу не идёт`() {
        // BM25 может высоко оценить соседние слова — но без подсвечиваемого
        // куска строка читается как глюк.
        val results = NoteSearch.search(
            listOf(
                entry("n1", "корм для кошки закончился"),
                entry("n2", "кошка спит на диване"),
            ),
            "корм",
        )
        assertEquals(listOf("n1"), results.map { it.entry.note.id })
    }

    @Test
    fun `лучшее совпадение выше свежего`() {
        // Запрос — вопрос, а не отрезок времени (11b): восемь записей из
        // десяти закрыты, и по времени наверх полезло бы свежее и неважное.
        val results = NoteSearch.search(
            listOf(
                entry("old", "дача дача дача весь день про дачу", at = 1_000),
                entry("new", "заехал на дачу на минуту", at = 2_000),
            ),
            "дача",
        )
        assertEquals("old", results.first().entry.note.id)
    }

    @Test
    fun `однобуквенный запрос — пустая выдача`() {
        // По одной букве искать значит подсветить пол-экрана.
        assertTrue(NoteSearch.search(listOf(entry("n1", "а тут всё есть")), "а").isEmpty())
    }

    @Test
    fun `несколько слов запроса — каждое подсвечено`() {
        val text = "корм кошке и наполнитель"
        val results = NoteSearch.search(listOf(entry("n1", text)), "корм наполнитель")
        val words = results.single().spans.map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("корм", "наполнитель"), words)
    }
}
