package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.NoteWithItems

/**
 * Поиск по записям (Р-18.5, макеты 11a–11b).
 *
 * Ходит **только по расшифровкам** — по словам человека, не по формулировкам
 * модели (решение дизайнера, 11b): совпадение в переписанном виде человек не
 * узнает, и подсвечивать будет нечего.
 *
 * Ранжирует BM25 — тем же индексом, что ищет связи между записями. Но отбор
 * жёстче ранжирования: BM25 находит «похожее», а поиску нужно «содержит» —
 * каждая заметка выдачи обязана нести совпадение, которое можно подсветить.
 * Похожая-но-без-совпадения строка в выдаче читается как глюк.
 */
object NoteSearch {

    data class Result(
        val entry: NoteWithItems,
        /** Диапазоны совпадений в расшифровке — под подсветку. */
        val spans: List<IntRange>,
        val score: Double,
    )

    /**
     * @param query что ищем; меньше [MIN_QUERY] знаков — пустая выдача,
     *   по одной букве искать значит подсветить пол-экрана
     */
    fun search(notes: List<NoteWithItems>, query: String): List<Result> {
        val words = query.trim().lowercase()
            .split(Regex("[^а-яёa-z0-9]+"))
            .filter { it.length >= MIN_QUERY }
        if (words.isEmpty()) return emptyList()

        val scores = Bm25(
            notes.map { Bm25.Doc(it.note.id, it.note.transcript.orEmpty()) }
        ).search(query, limit = MAX_RESULTS).associate { it.id to it.score }

        return notes.mapNotNull { entry ->
            val text = entry.note.transcript.orEmpty()
            val spans = spansOf(text, words)
            // Совпадение обязано быть буквальным: BM25 может высоко оценить
            // соседние слова, но без подсвечиваемого куска строка не идёт.
            if (spans.isEmpty()) return@mapNotNull null
            Result(entry, spans, scores[entry.note.id] ?: 0.0)
        }
            // По совпадению, при равном — свежее выше (11b): запрос — вопрос,
            // а не отрезок времени.
            .sortedWith(
                compareByDescending<Result> { it.score }
                    .thenByDescending { it.spans.size }
                    .thenByDescending { it.entry.note.createdAt }
            )
            .take(MAX_RESULTS)
    }

    /**
     * Диапазоны слов расшифровки, начинающихся с искомого слова.
     *
     * Префикс, а не вхождение: «дача» находит «даче» и «дачу», но не
     * «передачу» — совпадение в середине чужого слова человека путает.
     * Подсвечивается слово целиком: «даче», а не «дач-».
     */
    fun spansOf(text: String, words: List<String>): List<IntRange> {
        // Основа запроса — слово без окончания-гласной: «дача» находит «даче»
        // и «дачу». Меньше трёх знаков основу не режем: «он» не должен
        // подсвечивать «она», «они» и пол-экрана заодно.
        val stems = words.map { word ->
            word.trimEnd('а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я', 'ь', 'й')
                .takeIf { it.length >= 3 } ?: word
        }
        val lower = text.lowercase()
        val spans = mutableListOf<IntRange>()
        Regex("[а-яёa-z0-9]+").findAll(lower).forEach { token ->
            if (stems.any { token.value.startsWith(it) }) {
                spans += token.range
            }
        }
        return spans
    }

    private const val MIN_QUERY = 2

    /** Дальше выдачу не читают, а корпус — сотня записей. */
    private const val MAX_RESULTS = 50
}
