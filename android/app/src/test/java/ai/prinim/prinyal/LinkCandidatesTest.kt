package ai.prinim.prinyal

import ai.prinim.prinyal.data.NoteEntity
import ai.prinim.prinyal.domain.LinkCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отбор кандидатов — половина качества линковки: чего нет в списке, того не
 * будет и в ответе модели.
 */
class LinkCandidatesTest {

    private var clock = 1_000_000L

    private fun note(id: String, text: String, topic: String? = null, sibling: String? = null) =
        NoteEntity(
            id = id,
            createdAt = clock++,
            audioPath = "",
            transcript = text,
            topicId = topic,
            siblingId = sibling,
        )

    @Test
    fun `берёт и раздел, и похожих по словам`() {
        val me = note("me", "надо бы маркдаун поддержать в заметках", topic = "work")
        val corpus = listOf(
            me,
            note("same-topic", "совсем про другое, но тот же раздел", topic = "work"),
            note("same-words", "тесты на заметки с маркдауном"),
            note("alien", "лего про космос"),
        )
        val ids = LinkCandidates.of(me, corpus).map { it.id }
        assertTrue("раздел не попал: $ids", "same-topic" in ids)
        assertTrue("похожий не попал: $ids", "same-words" in ids)
        assertTrue("чужая попала: $ids", "alien" !in ids)
    }

    @Test
    fun `сама заметка и её половина в кандидаты не идут`() {
        // Половинки одной записи связаны родством, и линк между ними был бы
        // шумом (Р-15.5).
        val me = note("me", "маркдаун в заметках", sibling = "half")
        val corpus = listOf(me, note("half", "маркдаун в заметках, вторая половина"))
        assertTrue(LinkCandidates.of(me, corpus).isEmpty())
    }

    @Test
    fun `удалённые и неразобранные не предлагаются`() {
        val me = note("me", "маркдаун в заметках")
        val corpus = listOf(
            me,
            note("gone", "маркдаун тоже").copy(deletedAt = 1),
            note("silent", "").copy(transcript = null),
        )
        assertTrue(LinkCandidates.of(me, corpus).isEmpty())
    }

    @Test
    fun `кандидатов не больше восьми`() {
        val me = note("me", "маркдаун в заметках", topic = "work")
        val corpus = listOf(me) + (1..20).map {
            note("n$it", "маркдаун и заметки номер $it", topic = "work")
        }
        assertEquals(LinkCandidates.MAX, LinkCandidates.of(me, corpus).size)
    }

    @Test
    fun `начало записи режется по слову`() {
        val long = "нужно не забыть взять паспорт и зарядное устройство и ещё документы на машину"
        val opening = LinkCandidates.opening(long)
        assertTrue(opening.endsWith("…"))
        assertTrue("обрублено посреди слова: $opening", !opening.dropLast(1).endsWith(" "))
        assertTrue(opening.length <= LinkCandidates.OPENING + 1)
        assertEquals("короткая запись", LinkCandidates.opening("короткая запись"))
    }
}
