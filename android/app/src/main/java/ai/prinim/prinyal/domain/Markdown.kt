package ai.prinim.prinyal.domain

/**
 * Разбор и обеззараживание markdown для блока «Собрано» (Р-14.5, Д-4).
 *
 * Подмножество бедное намеренно: h2–h3, списки, жирный, курсив, инлайн-код.
 * Всё остальное — таблицы, картинки, ссылки, цитаты, нумерованные списки,
 * многострочные блоки кода, h1 и h4+ — вычищается до рендера.
 *
 * Почему санитайзер, а не «модель предупреждена»: текст сюда приходит от
 * языковой модели, то есть от стороны, которой мы не управляем. Ставить рендер
 * на честное слово генератора — это ставить его на удачу. HTML в ответе не
 * должен ни исполниться, ни показаться тегами; кривая разметка обязана
 * деградировать в плоский текст, а не уронить карточку.
 *
 * Своя реализация, а не библиотека: подмножество меньше любой библиотеки, зато
 * граница «что рендерим» видна целиком в одном файле и проверяется тестами.
 */
object Markdown {

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        data class Bullet(val text: String) : Block
    }

    /** Инлайн-кусок строки: обычный, жирный, курсив или код. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
    )

    private const val MAX_CHARS = 8_000

    /**
     * Убрать всё, чего не рендерим, и обезвредить остальное.
     *
     * @return текст только из разрешённой разметки; пустая строка, если после
     *         чистки ничего не осталось
     */
    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""

        var text = raw.take(MAX_CHARS)

        // Многострочные блоки кода: содержимое оставляем текстом, забор убираем.
        text = text.replace(Regex("```[a-zA-Z]*\\n?"), "")

        // HTML не должен ни исполниться, ни показаться тегами.
        text = text.replace(Regex("<[^>\\n]{1,200}>"), "")

        // Ссылки: остаётся подпись, адрес уходит. Картинки уходят целиком.
        text = text.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
        text = text.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")

        return text.lineSequence()
            .mapNotNull { line -> cleanLine(line) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun cleanLine(raw: String): String? {
        val line = raw.trimEnd()
        val trimmed = line.trim()

        // Горизонтальные линейки и строки таблиц выкидываем целиком: без них
        // текст читается, с ними — превращается в мусор из палочек.
        if (trimmed.matches(Regex("^([-*_]\\s*){3,}$"))) return null
        if (trimmed.startsWith("|") && trimmed.endsWith("|")) return null
        if (trimmed.matches(Regex("^\\|?[\\s:-]+\\|[\\s|:-]*$"))) return null

        // Цитаты и нумерованные списки теряют маркер, но не содержимое:
        // выбрасывать смысл вместе с разметкой было бы хуже.
        var result = trimmed.removePrefix(">").trim()
        result = result.replace(Regex("^\\d+[.)]\\s+"), "")

        // Заголовки вне h2–h3 опускаем до обычного текста.
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(result)
        if (heading != null) {
            val level = heading.groupValues[1].length
            val body = heading.groupValues[2].trim()
            if (body.isEmpty()) return null
            return if (level in 2..3) "#".repeat(level) + " " + body else body
        }

        // Вырезанная картинка или тег оставляют после себя двойной пробел —
        // на экране это заметная дыра в строке.
        return result.replace(Regex(" {2,}"), " ").ifEmpty { "" }
    }

    /** Разбор очищенного текста на блоки. */
    fun parse(clean: String): List<Block> = clean.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> null
                trimmed.startsWith("### ") -> Block.Heading(3, trimmed.removePrefix("### ").trim())
                trimmed.startsWith("## ") -> Block.Heading(2, trimmed.removePrefix("## ").trim())
                // Маркер списка — тире: галочки и буллеты продукту запрещены,
                // тире же читается как речь (Д-4).
                trimmed.startsWith("- ") || trimmed.startsWith("— ") || trimmed.startsWith("* ") ->
                    Block.Bullet(trimmed.drop(2).trim())
                else -> Block.Paragraph(trimmed)
            }
        }
        .filter { block ->
            when (block) {
                is Block.Heading -> block.text.isNotEmpty()
                is Block.Paragraph -> block.text.isNotEmpty()
                is Block.Bullet -> block.text.isNotEmpty()
            }
        }
        .toList()

    /**
     * Инлайн-разметка строки.
     *
     * Незакрытый маркер остаётся обычным текстом — это и есть деградация в
     * плоский текст вместо падения.
     */
    fun spans(line: String): List<Span> {
        val out = mutableListOf<Span>()
        val pattern = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|_(.+?)_|`(.+?)`")
        var cursor = 0

        pattern.findAll(line).forEach { match ->
            if (match.range.first > cursor) {
                out += Span(line.substring(cursor, match.range.first))
            }
            val bold = match.groupValues[1]
            val italic = match.groupValues[2].ifEmpty { match.groupValues[3] }
            val code = match.groupValues[4]
            out += when {
                bold.isNotEmpty() -> Span(bold, bold = true)
                italic.isNotEmpty() -> Span(italic, italic = true)
                else -> Span(code, code = true)
            }
            cursor = match.range.last + 1
        }
        if (cursor < line.length) out += Span(line.substring(cursor))
        return out.filter { it.text.isNotEmpty() }
    }
}
