package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.NoteWithItems
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Что показывает лента: фильтр, группировка по дням и один статус на запись
 * (Д-24, Д-25).
 *
 * Вынесено из разметки, потому что это правила, а не рисование. Главное из них
 * — **политика возврата принадлежит записи, а не пункту**. Человек наговорил
 * комок один раз, и продукт возвращается к нему один раз; печатать «в плане ·
 * просто сохраню» под каждым из одиннадцати пунктов значит разбивать его речь
 * служебным текстом на каждом шагу. Под пунктом статус остаётся только там, где
 * он **отличается** от общего.
 */
object FeedView {

    /** Пять слов в строке фильтра. Шестое («просрочено») появляется по факту. */
    enum class Filter(val wire: String) {
        ALL("all"), PLANNED("planned"), RETURNING("returning"), DONE("done"), BURIED("buried")
    }

    /** Сколько живых пунктов печатается в ленте, прежде чем свернуться в остаток. */
    const val MAX_ITEMS = 3

    /** Закрытое показываем за неделю: дальше — «показать раньше». */
    const val CLOSED_WINDOW_DAYS = 7L

    /**
     * Запись, приготовленная к показу.
     *
     * @param shown пункты, которые печатаются текстом
     * @param restPlanned сколько живых не поместилось
     * @param restDone сколько закрытых свернулось в остаток
     * @param allClosed у записи не осталось живого — она печатается одной строкой
     * @param matched сколько пунктов прошло фильтр (для шапки «1 из 4»)
     * @param total сколько пунктов в записи всего
     */
    data class Row(
        val entry: NoteWithItems,
        val shown: List<ItemEntity>,
        val restPlanned: Int,
        val restDone: Int,
        val restGone: Int,
        val allClosed: Boolean,
        val matched: Int,
        val total: Int,
    ) {
        val filtered: Boolean get() = matched != total
    }

    /** Заголовок дня или срока возврата. */
    data class Section(val kind: Kind, val date: LocalDate?, val rows: List<Row>) {
        enum class Kind { TODAY, DAY, TOMORROW, THIS_WEEK, LATER }
    }

    fun matches(item: ItemEntity, filter: Filter, now: Instant): Boolean {
        val state = ItemState.of(item.state)
        return when (filter) {
            Filter.ALL -> true
            Filter.PLANNED -> state in LIVE
            // «Вернусь» — про то, у чего есть назначенный срок в будущем.
            // Пункт без срока живой, но возвращаться к нему продукт не обещал.
            Filter.RETURNING -> state in LIVE && item.dueAt?.let { it > now.toEpochMilli() } == true
            Filter.DONE -> state == ItemState.DONE
            Filter.BURIED -> state in setOf(ItemState.DISMISSED, ItemState.EXPIRED)
        }
    }

    /**
     * @return записи, прошедшие фильтр, разложенные по секциям
     */
    fun sections(
        notes: List<NoteWithItems>,
        filter: Filter,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Section> {
        val today = now.atZone(zone).toLocalDate()

        val rows = notes.mapNotNull { entry ->
            // Закрытая запись из «всего» уходит: строка «всё сделано» без
            // текста не несёт ничего — ни дела, ни новости. Смотреть закрытое
            // человек приходит фильтром, и там оно разворачивается целиком.
            //
            // Записи без пунктов при этом остаются: там ещё может быть речь,
            // которую не разобрали, и прятать её значило бы терять сказанное.
            if (filter == Filter.ALL && entry.items.isNotEmpty() &&
                entry.items.none { ItemState.of(it.state) in LIVE }
            ) {
                return@mapNotNull null
            }
            val matched = entry.items.filter { matches(it, filter, now) }
            // Запись без единого подходящего пункта из выдачи уходит целиком:
            // показывать её пустой шапкой значит отвечать «вот записи» на вопрос
            // «где похороненное».
            if (filter != Filter.ALL && matched.isEmpty()) return@mapNotNull null
            row(entry, matched, filter)
        }

        // Закрытое ограничено неделей: человек пришёл смотреть, что сделано, а
        // не листать историю.
        val limited = if (filter in setOf(Filter.DONE, Filter.BURIED)) {
            val edge = now.minusSeconds(CLOSED_WINDOW_DAYS * 24 * 60 * 60).toEpochMilli()
            rows.filter { it.entry.note.createdAt >= edge }
        } else {
            rows
        }

        return if (filter == Filter.RETURNING) byReturn(limited, today, zone) else byDay(limited, today, zone)
    }

    private fun row(entry: NoteWithItems, matched: List<ItemEntity>, filter: Filter): Row {
        val pool = if (filter == Filter.ALL) entry.items else matched
        val live = pool.filter { ItemState.of(it.state) in LIVE }
        val done = pool.count { ItemState.of(it.state) == ItemState.DONE }
        val gone = pool.count { ItemState.of(it.state) in CLOSED_GONE }

        // Живого нет — запись закрыта и печатается одной строкой. Цифра без
        // текста («1 сделано») читается как сбой, слово — как состояние.
        val allClosed = live.isEmpty() && (done + gone) > 0

        val shown = if (allClosed) emptyList() else live.take(MAX_ITEMS)
        return Row(
            entry = entry,
            shown = shown,
            restPlanned = (live.size - shown.size).coerceAtLeast(0),
            // Счётчики закрытого держим всегда, а не обнуляем у закрытой
            // записи: по ним шапка выбирает слово — «всё сделано» или «всё
            // похоронено». Обнулив их, я получил «всё сделано» под фильтром
            // «похоронено» — продукт называл похороненное сделанным.
            restDone = done,
            restGone = gone,
            allClosed = allClosed,
            matched = matched.size,
            total = entry.items.size,
        )
    }

    private fun byDay(rows: List<Row>, today: LocalDate, zone: ZoneId): List<Section> =
        rows.groupBy { Instant.ofEpochMilli(it.entry.note.createdAt).atZone(zone).toLocalDate() }
            .toSortedMap(reverseOrder())
            .map { (day, list) ->
                Section(
                    kind = if (day == today) Section.Kind.TODAY else Section.Kind.DAY,
                    date = day,
                    rows = list,
                )
            }

    /**
     * «Вернусь» разбивается сроком возврата, а не датой записи: здесь важно
     * когда придёт, а не когда сказано. Это два заголовка вместо двух лишних
     * слов в строке фильтра.
     */
    private fun byReturn(rows: List<Row>, today: LocalDate, zone: ZoneId): List<Section> {
        fun soonest(row: Row): LocalDate? = row.entry.items
            .mapNotNull { it.dueAt }
            .minOrNull()
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

        val buckets = linkedMapOf(
            Section.Kind.TOMORROW to mutableListOf<Row>(),
            Section.Kind.THIS_WEEK to mutableListOf(),
            Section.Kind.LATER to mutableListOf(),
        )
        rows.sortedBy { soonest(it) ?: LocalDate.MAX }.forEach { row ->
            val at = soonest(row)
            val kind = when {
                at == null -> Section.Kind.LATER
                !at.isAfter(today.plusDays(1)) -> Section.Kind.TOMORROW
                !at.isAfter(today.plusDays(CLOSED_WINDOW_DAYS)) -> Section.Kind.THIS_WEEK
                else -> Section.Kind.LATER
            }
            buckets.getValue(kind) += row
        }
        return buckets.filterValues { it.isNotEmpty() }
            .map { (kind, list) -> Section(kind, null, list) }
    }

    private val LIVE = setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)
    private val CLOSED_GONE = setOf(ItemState.DISMISSED, ItemState.EXPIRED)
}
