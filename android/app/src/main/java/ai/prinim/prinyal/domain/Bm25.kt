package ai.prinim.prinyal.domain

import kotlin.math.ln

/**
 * Поиск похожих заметок по словам (Р-15.15).
 *
 * Спайк сравнил это с локальными эмбеддингами `multilingual-e5-small` на живом
 * корпусе владельца: преимущества у модели не нашлось, а весит она 123 МБ при
 * APK в 393 (`docs/spike-1_0_2-semsearch.md`). При нулевой разнице платит
 * проигравший — поэтому топливо для авто-линковки и починки структуры даёт
 * BM25, а эмбеддинги переехали в 1.1.
 *
 * Формула ровно та, на которой считались числа отчёта: k1 = 1,2, b = 0,75.
 * Разойтись им нельзя — иначе отчёт описывает не то, что работает.
 *
 * Своя реализация, а не библиотека: здесь двадцать строк, а зависимость
 * принесла бы свои представления о токенизации русского.
 */
class Bm25(private val docs: List<Doc>) {

    data class Doc(val id: String, val text: String)

    /** Найденное. [score] сравним только внутри одного запроса. */
    data class Hit(val id: String, val score: Double)

    private val terms: List<List<String>> = docs.map { tokenize(it.text) }
    private val counts: List<Map<String, Int>> = terms.map { list ->
        list.groupingBy { it }.eachCount()
    }
    private val avgLength: Double =
        if (terms.isEmpty()) 0.0 else terms.sumOf { it.size } / terms.size.toDouble()
    private val docFreq: Map<String, Int> = buildMap {
        terms.forEach { list -> list.toSet().forEach { merge(it, 1, Int::plus) } }
    }

    /**
     * @param queryId заметка, для которой ищем похожие; сама она из выдачи
     *        исключается — «связано само с собой» не связь
     * @param limit сколько кандидатов вернуть
     * @return найденное по убыванию, без нулевых совпадений
     */
    fun similarTo(queryId: String, limit: Int = DEFAULT_LIMIT): List<Hit> {
        val index = docs.indexOfFirst { it.id == queryId }
        if (index < 0) return emptyList()
        return search(terms[index], exclude = index, limit = limit)
    }

    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Hit> =
        search(tokenize(query), exclude = -1, limit = limit)

    private fun search(query: List<String>, exclude: Int, limit: Int): List<Hit> {
        if (docs.isEmpty()) return emptyList()
        val unique = query.toSet()
        return docs.indices.asSequence()
            .filter { it != exclude }
            .map { i -> Hit(docs[i].id, score(unique, i)) }
            // Ноль — это «ни одного общего слова». Такой кандидат хуже, чем его
            // отсутствие: он занимает место в списке из восьми и тратит токены
            // разбора на заметку, к которой отношения не имеет.
            .filter { it.score > 0.0 }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }

    private fun score(query: Set<String>, doc: Int): Double {
        val length = terms[doc].size
        if (length == 0) return 0.0
        val counted = counts[doc]
        var sum = 0.0
        query.forEach { term ->
            val tf = counted[term] ?: return@forEach
            val df = docFreq[term] ?: return@forEach
            val idf = ln(1 + (docs.size - df + 0.5) / (df + 0.5))
            sum += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / avgLength))
        }
        return sum
    }

    companion object {
        private const val K1 = 1.2
        private const val B = 0.75
        const val DEFAULT_LIMIT = 8

        private val WORD = Regex("[а-яёa-z0-9]+")

        /**
         * Слова с грубым усечением вместо морфологии.
         *
         * «Заметку», «заметки», «заметок» обязаны совпасть, иначе поиск по
         * русскому тексту не работает вовсе. Настоящая лемматизация — это
         * словарь на мегабайты; усечение до пяти букв ошибается на редких парах
         * («столица» / «столик»), но эти ошибки дешевле, чем несовпадение
         * падежей на каждой второй заметке.
         *
         * Короткие слова не трогаем: у них рубить нечего, а обрезка склеила бы
         * несвязанное.
         */
        fun tokenize(text: String): List<String> =
            WORD.findAll(text.lowercase())
                .map { it.value }
                .map { if (it.length > 6) it.take(5) else it }
                .toList()
    }
}
