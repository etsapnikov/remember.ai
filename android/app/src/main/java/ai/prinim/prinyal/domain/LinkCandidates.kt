package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.NoteEntity

/**
 * Кого показать модели, когда ищем связи (Р-15.11).
 *
 * Модель не видит корпуса — она видит только то, что мы ей положили. Значит
 * отбор кандидатов и есть половина качества линковки: чего в списке нет, того
 * не будет и в ответе.
 *
 * Два источника, и они разные по природе:
 *
 *  - **тот же раздел** — потому что человек уже сказал, что записи про одно;
 *    это его суждение, и оно надёжнее любого нашего счёта;
 *  - **BM25** — потому что раздела может не быть вовсе, а общее редкое слово
 *    («маркдаун», «Чупрунов») связывает вернее, чем общая тема.
 *
 * Больше восьми не даём: список кандидатов уходит в промпт целиком, и каждый
 * лишний — это токены на каждой записи. Восемь — потолок из ТЗ, и он же примерно
 * то, что модель способна честно сравнить, не начав выбирать наугад.
 */
object LinkCandidates {

    const val MAX = 8

    /** Сколько знаков записи показываем — и модели в списке, и человеку в блоке. */
    const val OPENING = 60

    /**
     * Начало записи — то, по чему её узнают.
     *
     * Первые слова, а не «умная» выжимка: выжимка требует модели, а узнать свою
     * запись человек умеет по первой фразе. Обрезка по слову: обрубок посреди
     * слова читается как поломка.
     */
    fun opening(transcript: String?): String {
        val text = transcript.orEmpty().trim().replace(Regex("\\s+"), " ")
        if (text.length <= OPENING) return text
        val cut = text.take(OPENING)
        val space = cut.lastIndexOf(' ')
        return (if (space >= OPENING / 2) cut.take(space) else cut).trimEnd(' ', ',', '.') + "…"
    }

    /** Сколько мест отдаём разделу, прежде чем добирать поиском. */
    private const val TOPIC_SHARE = 4

    /**
     * @param note заметка, для которой ищем связи
     * @param corpus все живые заметки, включая саму [note]
     * @return до [MAX] кандидатов, свежие впереди
     */
    fun of(note: NoteEntity, corpus: List<NoteEntity>): List<NoteEntity> {
        val others = corpus.filter {
            it.id != note.id &&
                it.deletedAt == null &&
                !it.transcript.isNullOrBlank() &&
                // Половинки одной записи связывать незачем: они и так связаны
                // родством, и линк между ними был бы шумом (Р-15.5).
                it.id != note.siblingId &&
                it.siblingId != note.id
        }
        if (others.isEmpty()) return emptyList()

        val chosen = LinkedHashMap<String, NoteEntity>()

        if (note.topicId != null) {
            others.asSequence()
                .filter { it.topicId == note.topicId }
                .sortedByDescending { it.createdAt }
                .take(TOPIC_SHARE)
                .forEach { chosen[it.id] = it }
        }

        val index = Bm25(others.map { Bm25.Doc(it.id, it.transcript.orEmpty()) } +
            Bm25.Doc(note.id, note.transcript.orEmpty()))
        val byId = others.associateBy { it.id }
        index.similarTo(note.id, limit = MAX).forEach { hit ->
            if (chosen.size >= MAX) return@forEach
            byId[hit.id]?.let { chosen.putIfAbsent(it.id, it) }
        }

        return chosen.values.take(MAX).sortedByDescending { it.createdAt }
    }
}
