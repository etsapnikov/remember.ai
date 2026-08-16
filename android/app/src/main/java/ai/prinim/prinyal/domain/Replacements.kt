package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.ReplacementEntity

/**
 * Словарь автозамен (Р-14.2).
 *
 * Чинит не текст, а слух: распознавание стабильно ошибается на одних и тех же
 * именах и терминах — «гига ам», «за помню», «соник лору». Правило работает в
 * двух местах сразу, и это не дублирование, а разные болезни: замена в
 * транскрипте чинит **написание**, тот же словарь в промпте чинит **понимание**
 * (модель видит «GigaAM» и знает, что это одно слово, а не два).
 *
 * Логика чистая и без Android — её можно проверить, не поднимая устройство.
 */
object Replacements {

    /** Что заменили и сколько раз — чтобы обновить счётчики правил. */
    data class Applied(val text: String, val hits: Map<String, Int>)

    /**
     * Применить словарь к транскрипту.
     *
     * Правила идут от длинных к коротким: иначе правило на одно слово съело бы
     * фразу из трёх, и «соник лору» никогда не починилось бы, потому что «лору»
     * сработало раньше.
     *
     * Границы слова обязательны. Без них замена «юля» испортила бы «юлия»,
     * а замена «ам» — половину словаря.
     */
    fun apply(text: String, rules: List<ReplacementEntity>): Applied {
        if (text.isBlank() || rules.isEmpty()) return Applied(text, emptyMap())

        // Один проход по исходному тексту, а не по результату предыдущей замены.
        //
        // Последовательные проходы выглядят проще, но врут: правило «лору → к
        // лору» после «соник лору → Соню к лору» срабатывает **на уже
        // подставленном** тексте и даёт «Соню к к лору». Поэтому сначала
        // собираем все совпадения на исходнике, затем гасим пересечения в пользу
        // длинного правила — каждый кусок речи меняется ровно один раз.
        data class Match(val start: Int, val end: Int, val rule: ReplacementEntity)

        val found = mutableListOf<Match>()
        rules.forEach { rule ->
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(rule.fromPhrase.trim()) +
                    "(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            )
            pattern.findAll(text).forEach { found += Match(it.range.first, it.range.last + 1, rule) }
        }
        if (found.isEmpty()) return Applied(text, emptyMap())

        val chosen = mutableListOf<Match>()
        found.sortedWith(compareByDescending<Match> { it.end - it.start }.thenBy { it.start })
            .forEach { candidate ->
                val overlaps = chosen.any { candidate.start < it.end && it.start < candidate.end }
                if (!overlaps) chosen += candidate
            }

        val hits = mutableMapOf<String, Int>()
        val out = StringBuilder()
        var cursor = 0
        chosen.sortedBy { it.start }.forEach { match ->
            out.append(text, cursor, match.start)
            val source = text.substring(match.start, match.end)
            // Заглавная первой буквы сохраняется: «Юля» в начале фразы остаётся
            // «Юля», а не превращается в строчное посреди текста.
            out.append(
                if (source.firstOrNull()?.isUpperCase() == true) {
                    match.rule.toPhrase.replaceFirstChar { it.uppercase() }
                } else {
                    match.rule.toPhrase
                }
            )
            cursor = match.end
            hits[match.rule.id] = (hits[match.rule.id] ?: 0) + 1
        }
        out.append(text, cursor, text.length)

        return Applied(out.toString(), hits)
    }

    /**
     * Глоссарий для промпта.
     *
     * Отдаём только пары, а не инструкцию: длинная просьба «пиши правильно» на
     * каждом разборе стоит токенов и рассеивает внимание модели сильнее, чем
     * помогает.
     */
    fun glossary(rules: List<ReplacementEntity>): List<String> =
        rules.map { "${it.fromPhrase} → ${it.toPhrase}" }

    /** Нормализация имени правила: по ней ищется дубль. */
    fun norm(phrase: String): String =
        phrase.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Словарь чинит слух на именах и терминах, а не переписывает предложения. */
    const val MAX_WORDS = 3

    fun fits(phrase: String): Boolean {
        val clean = phrase.trim()
        return clean.isNotEmpty() && clean.split(Regex("\\s+")).size <= MAX_WORDS
    }
}
