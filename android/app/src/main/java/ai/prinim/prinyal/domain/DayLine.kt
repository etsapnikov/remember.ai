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
        val corpus = tokens(transcript).map(::stem).toSet()
        val words = tokens(line)
        return words.isNotEmpty() && words.all { stem(it) in corpus }
    }

    /**
     * Основа слова — грубо, отсечением окончания.
     *
     * Сравнение точных словоформ было буквальным исполнением правила «только
     * слова человека» и убивало саму механику: человек говорит «занимались
     * организацией», модель сжимает в «организация обучения», проверка не
     * находит «организация» среди слов и отбрасывает **всю** строку. В базе
     * владельца день так и остался нетронутой расшифровкой на 108 знаков —
     * выглядело как «модель не отработала», хотя она отработала и её ответ
     * выбросили мы.
     *
     * Падеж — не выдумка, а грамматика. Выдумка — это чужое слово, и оно
     * ловится основой так же надёжно.
     */
    private fun stem(word: String): String {
        if (word.length <= 4) return word
        val cut = word.trimEnd('а', 'е', 'ё', 'и', 'о', 'у', 'ы', 'э', 'ю', 'я', 'й', 'ь')
        return if (cut.length >= 4) cut.take(STEM) else word.take(STEM)
    }

    /** Длиннее основы не сравниваем: дальше начинаются суффиксы. */
    private const val STEM = 6

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
