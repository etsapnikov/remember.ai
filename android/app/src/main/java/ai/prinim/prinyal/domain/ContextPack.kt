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

    /**
     * Сколько знаков расшифровки берём.
     *
     * Было 400 — на живых записях это обрывало мысль на середине, и пак терял
     * ровно то, ради чего собирался. Полторы тысячи покрывают запись на минуту
     * речи целиком.
     */
    private const val RAW = 1500

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

        // Дальше — запись за записью: что продукт понял и что человек сказал.
        //
        // Сводных разделов («Решения», «Факты и вводные», «Открытые вопросы»)
        // здесь больше нет. Они собирались по типу пункта через весь корпус, и
        // на живых темах выходила мешанина: решение из июля стояло рядом с
        // фактом из августа без всякой связи между ними, а понять, откуда что
        // взялось, можно было только сверив даты с «Сырьём» внизу. Владелец
        // назвал это «билебердой», и он прав: пак ценен ровно тем, что в нём
        // слова человека и то, что из них поняли, — рядом друг с другом.
        live.reversed().forEach { source -> noteBlock(out, source, zone) }

        return out.toString()
    }

    private val OPEN = setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)

    /** Что не бывает «открытым вопросом»: решение принято, факт не действие. */
    private val CLOSED_TYPES = setOf(ItemType.DECISION, ItemType.FACT, ItemType.THOUGHT)

    /**
     * Одна запись: заголовок с датой, что понято, что сказано.
     *
     * Порядок внутри блока такой же, как на экране карточки, и это не
     * совпадение: человек уже знает, где что искать. Расшифровка идёт цитатой
     * (`>`), а не абзацем: в любом просмотрщике markdown это даёт вертикальную
     * черту слева, и границу записи видно, не читая текста.
     */
    private fun noteBlock(out: StringBuilder, source: Source, zone: ZoneId) {
        val text = source.note.transcript.orEmpty().trim()
        if (text.isEmpty()) return

        // Время рядом с датой: в один день записей бывает несколько, и без
        // него два блока подряд выглядят как повтор одного.
        out.append('\n').append("## ").append(day(source.note.createdAt, zone))
            .append(" · ").append(time(source.note.createdAt, zone)).append('\n')

        // Саммари — то, что модель уже написала словами человека. Если его нет
        // (в записи-делах его и не бывает), сводкой служат сами пункты: это то
        // же «Что понял», что и на экране, а не новый пересказ.
        val body = source.note.bodyMd?.trim().orEmpty()
        if (body.isNotEmpty()) {
            out.append('\n').append(body).append('\n')
        } else if (source.items.isNotEmpty()) {
            out.append('\n')
            source.items.forEach { item ->
                out.append("- ").append(item.text)
                if (!item.who.isNullOrBlank()) out.append(" · ").append(item.who)
                out.append('\n')
            }
        }

        out.append('\n')
        // Каждая строка цитаты со своим маркером: без него длинный текст
        // ломается в первом же переносе и перестаёт быть цитатой.
        text.take(RAW).split('\n').forEach { line ->
            out.append("> ").append(line.trim()).append('\n')
        }
        if (text.length > RAW) out.append("> …").append('\n')
    }

    /** Сколько знаков первого пункта уходит в подзаголовок записи. */
    private const val TITLE = 60

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

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun time(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(HHMM)

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
