package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Контекст-пак (Р-15.13): всё, что продукт знает по теме, одним файлом.
 *
 * **Собирается кодом, не моделью, и это главное решение здесь.** Пак человек
 * уносит наружу — в переписку, в документ, на встречу — и там за него отвечает
 * уже он. Утверждение, которого в корпусе не было, в такой бумаге дороже всего:
 * заметить подмену невозможно, потому что читатель верит, что читает свои же
 * слова. Требование ТЗ «только корпус, слова владельца» выполнимо буквально
 * лишь тогда, когда пересказывать некому.
 *
 * Отсюда же форма: цитаты и списки, а не связный текст. Связный текст пришлось
 * бы сочинять.
 *
 * Дата стоит у каждого утверждения — без неё «решили переносить релиз» не
 * читается: непонятно, это позавчерашнее решение или прошлогоднее.
 */
object ContextPack {

    /** Сколько знаков сырья показываем на заметку. */
    private const val RAW = 400

    /** Сколько заметок цитируем в «Сырьё» — дальше файл перестают читать. */
    private const val RAW_NOTES = 12

    data class Source(
        val note: NoteEntity,
        val items: List<ItemEntity>,
    )

    /**
     * @param title как назвать пак — имя раздела или запрошенная тема
     * @param sources заметки темы со своими пунктами, в любом порядке
     */
    fun build(
        title: String,
        sources: List<Source>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val live = sources.filter { !it.note.transcript.isNullOrBlank() }
            .sortedBy { it.note.createdAt }
        val out = StringBuilder()

        out.append("# ").append(title).append('\n')
        if (live.isEmpty()) {
            // Пустая тема даёт честно короткий файл. Расписать её нечем, а
            // разделы-заглушки создают впечатление, что материал есть.
            out.append('\n').append("Записей по теме нет.").append('\n')
            return out.toString()
        }

        val from = day(live.first().note.createdAt, zone)
        val to = day(live.last().note.createdAt, zone)
        out.append('\n')
            .append(live.size).append(" ").append(notesWord(live.size))
            .append(" · ").append(from)
        if (from != to) out.append(" — ").append(to)
        out.append('\n')

        section(out, "Решения", live.flatMap { source ->
            source.items
                .filter { ItemType.of(it.type) == ItemType.DECISION }
                .map { line(it.text, it.who, source.note.createdAt, zone) }
        })

        section(out, "Факты и вводные", live.flatMap { source ->
            source.items
                .filter { ItemType.of(it.type) == ItemType.FACT }
                .map { line(it.text, null, source.note.createdAt, zone) }
        })

        section(out, "Открытые вопросы", live.flatMap { source ->
            source.items
                .filter { ItemState.of(it.state) in OPEN && ItemType.of(it.type) !in CLOSED_TYPES }
                .map { item ->
                    val age = daysBetween(source.note.createdAt, now.toEpochMilli())
                    // Возраст пункта — часть вопроса: «висит с июля» и «сказано
                    // вчера» требуют разного разговора.
                    "- ${item.text} — ${day(source.note.createdAt, zone)}, " +
                        "$age ${daysWord(age)} назад"
                }
        })

        // Сырьё последним: это то, чем можно проверить всё, что выше.
        section(out, "Сырьё", live.takeLast(RAW_NOTES).reversed().map { source ->
            "**${day(source.note.createdAt, zone)}** — " +
                "«${source.note.transcript.orEmpty().trim().take(RAW)}»"
        })

        return out.toString()
    }

    private val OPEN = setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)

    /** Что не бывает «открытым вопросом»: решение принято, факт не действие. */
    private val CLOSED_TYPES = setOf(ItemType.DECISION, ItemType.FACT, ItemType.THOUGHT)

    private fun section(out: StringBuilder, title: String, lines: List<String>) {
        // Пустой раздел не рисуется вовсе. Заголовок без содержимого обещает
        // материал, которого нет, и в чужих руках читается как пропажа.
        if (lines.isEmpty()) return
        out.append('\n').append("## ").append(title).append('\n').append('\n')
        lines.forEach { out.append(it).append('\n') }
    }

    private fun line(text: String, who: String?, at: Long, zone: ZoneId): String {
        val whoPart = if (who.isNullOrBlank()) "" else ", с кем: $who"
        return "- $text — ${day(at, zone)}$whoPart"
    }

    private val DAY: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))

    private fun day(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DAY)

    private fun daysBetween(from: Long, to: Long): Int =
        ((to - from) / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)

    private fun notesWord(n: Int): String = plural(n, "запись", "записи", "записей")

    private fun daysWord(n: Int): String = plural(n, "день", "дня", "дней")

    private fun plural(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        if (mod100 in 11..14) return many
        return when (n % 10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }

    /** Имя файла: тема и день, чтобы паки одной темы не затирали друг друга. */
    fun fileName(title: String, today: LocalDate = LocalDate.now()): String {
        val slug = title.lowercase()
            .replace(Regex("[^а-яёa-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "context" }
        return "$slug-$today.md"
    }
}
