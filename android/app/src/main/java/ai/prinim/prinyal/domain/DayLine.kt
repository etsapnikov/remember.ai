package ai.prinim.prinyal.domain

/**
 * Строка дня (Р-18.2, макет 12d): впечатление, сжатое из ответа.
 *
 * Правило одно и оно жёсткое: **своими словами продукт про день не говорит
 * никогда — это чужая память.** Модель выбирает куски сказанного и склеивает;
 * проверка, что каждое слово строки взято из ответа, — здесь, а не в промпте.
 * Не прошла — печатаем начало ответа как есть, без сжатия.
 */
object DayLine {

    /** Цель — до 60 знаков; потолок — 90, дальше строка не сжатие, а пересказ. */
    const val TARGET = 60
    const val CAP = 90

    /**
     * @param candidate что предложила модель; null — модель молчит или недоступна
     * @return строка для списка «Дней»
     */
    fun of(transcript: String, candidate: String?): String {
        val line = candidate?.trim()
            ?.trim('"', '«', '»', '.')
            ?.takeIf { it.isNotEmpty() && it.length <= CAP && wordsFromCorpus(it, transcript) }
        return line ?: opening(transcript)
    }

    /**
     * Каждое слово строки есть в ответе. Порядок не проверяется: «склеить
     * куски» — законно, выдумать слово — нет.
     */
    fun wordsFromCorpus(line: String, transcript: String): Boolean {
        val corpus = tokens(transcript).toSet()
        val words = tokens(line)
        return words.isNotEmpty() && words.all { it in corpus }
    }

    /**
     * Начало ответа как есть. Режем по границе слова, многоточия не ставим:
     * обрезки в «Днях» нет нигде (12d) — но и врать, что это весь ответ,
     * незачем: полный текст в раскрытии.
     */
    fun opening(transcript: String): String {
        val clean = transcript.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= CAP) return clean
        val cut = clean.take(CAP)
        return cut.substringBeforeLast(' ', cut)
    }

    private fun tokens(text: String): List<String> =
        Regex("[а-яёa-z0-9]+").findAll(text.lowercase()).map { it.value }.toList()
}
