package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.NoteWithItems
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.components.SwipeRevealRow
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Touch
import ai.prinim.prinyal.ui.theme.tap
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import ai.prinim.prinyal.domain.FeedView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import ai.prinim.prinyal.data.NoteEntity
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Лента (спека R1.1 §2). Записи списком без карточек-коробок — рамки не несли
 * информации; разделитель — хайрлайн 1dp. Глифов типов нет (вариант А): ценность
 * пункта — его текст и план, тип остался только чипами в шите правки.
 *
 * Удаление — свайп влево + тап по зоне, undo снекбаром 6 с. Похороны здесь не
 * живут: похоронить — про пункт, удалить — про мусор (§2.3).
 */
@Composable
fun FeedScreen(vm: AppViewModel, onOpenNote: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val notes by vm.feed.collectAsState()
    val llmEnabled by vm.llmEnabled.collectAsState()
    val ask by vm.askCandidate.collectAsState()
    val merge by vm.mergeCandidate.collectAsState()
    // Имена разделов для строки заметки: раздел виден там же, где время (Р-15.3).
    val topics by vm.topics.collectAsState()
    val topicNames = remember(topics) { topics.associate { it.id to it.name } }

    // Отсчёт трёх дней тишины идёт с показа, а не с ответа: увидел — значит
    // спросили, даже если человек прошёл мимо.
    LaunchedEffect(ask?.id) { ask?.let { vm.markAsked(it.id) } }
    // Позиция списка живёт во вьюмодели, а не в композиции: возвращаясь из
    // карточки, человек обязан оказаться там, откуда ушёл. rememberLazyListState
    // умирает вместе с экраном, и лента отматывалась в начало — а с сотней
    // записей это значит «найди заново».
    val listState = rememberLazyListState(vm.feedIndex, vm.feedOffset)
    DisposableEffect(listState) {
        onDispose {
            vm.feedIndex = listState.firstVisibleItemIndex
            vm.feedOffset = listState.firstVisibleItemScrollOffset
        }
    }
    val filter by vm.feedFilter.collectAsState()

    // Одновременно открыта максимум одна зона свайпа.
    var openKey by remember { mutableStateOf<String?>(null) }

    // Начало скролла закрывает открытую зону (спека §2.2).
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) openKey = null
    }

    val junk = notes.filter { it.isJunk() }

    Column(Modifier.fillMaxSize()) {
        // §2.4: выключенный разбор — одна meta-строка под заголовком, не бейджи.
        if (!llmEnabled) {
            Row(Modifier.padding(horizontal = Space.screen, vertical = Space.xs)) {
                MetaText(
                    text = stringResource(R.string.feed_parsing_off),
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier.tap { vm.setLlmEnabled(true) },
                )
            }
        }

        if (notes.isEmpty()) {
            EmptyFeed()
            return@Column
        }

        // Строка фильтра (Д-25) — вторая строка шапки и только на «Записях».
        // Поиск открыт — лента уступает место выдаче, фильтр уходит: два
        // способа отбирать записи одновременно не работают (макет 11a).
        val searchQuery by vm.searchQuery.collectAsState()
        if (searchQuery != null) {
            SearchResults(vm, query = searchQuery.orEmpty(), onOpenNote = onOpenNote)
            return@Column
        }

        FilterRow(filter, onPick = { vm.setFeedFilter(it) })

        // Лента — единственный экран, который умеет показать запись дважды:
        // её обычные пункты в дне, её повторы под «ПОВТОРЯЮТСЯ».
        val sections = remember(notes, filter) {
            FeedView.sections(notes, filter, groupRepeats = true)
        }
        val closed = remember(notes) { FeedView.closedCount(notes) }

        if (sections.isEmpty()) {
            FilteredEmpty(filter)
            return@Column
        }

        // Сводка под фильтром: сколько записей и пунктов сейчас видно. В покое
        // её нет — «всё» и так значит всё.
        if (filter != FeedView.Filter.ALL) {
            val rows = sections.sumOf { it.rows.size }
            val items = sections.sumOf { section -> section.rows.sumOf { it.matched } }
            // Счётчик говорит про повторы прямо: «7 пунктов · 2 повторяются».
            // Без этой цифры вечные пункты молча раздували счёт долгов.
            val repeats = sections
                .filter { it.kind == FeedView.Section.Kind.REPEATING }
                .sumOf { section -> section.rows.sumOf { it.matched } }
            MetaText(
                text = buildList {
                    add(pluralStringResource(R.plurals.feed_summary_notes, rows, rows))
                    add(pluralStringResource(R.plurals.feed_summary_items, items, items))
                    if (repeats > 0) add(pluralStringResource(R.plurals.repeat_count, repeats, repeats))
                }.joinToString(" · "),
                color = Prinyal.colors.inkFaint,
                modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
            )
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = Space.xs, bottom = Space.xxl),
        ) {
            // Групповая уборка: появляется при ≥ 3 записях без пунктов.
            if (junk.size >= 3 && filter == FeedView.Filter.ALL) {
                item(key = "junk-sweep") {
                    JunkSweepRow(count = junk.size, onSweep = { vm.sweepJunk() })
                }
            }

            // Вопросы продукта — наверху ленты и по одному за раз (Д-30).
            // Доспрос идёт первым: сначала узнать, кто это, и только потом
            // выяснять, не один ли это человек.
            ask?.let { person ->
                item(key = "ask-${person.id}") {
                    AskCard(
                        name = person.name,
                        onTell = { vm.tellAbout(context, person.name, person.id) },
                        onDecline = { vm.declinePerson(person.id) },
                    )
                    Hairline()
                }
            }
            if (ask == null) {
                merge?.let { (first, second) ->
                    item(key = "merge-${first.id}") {
                        MergeAskCard(
                            first = first.name,
                            second = second.name,
                            onSame = { vm.mergePeople(second, first) },
                            onApart = { vm.keepApart(first, second) },
                        )
                        Hairline()
                    }
                }
            }

            sections.forEach { section ->
                item(key = "day-${section.kind}-${section.date}") {
                    DayHeader(section)
                }
                section.rows.forEach { row ->
                    // Ключ с разделом: запись с повтором и обычными пунктами
                    // печатается дважды — своими пунктами в дне и своим
                    // повтором под «ПОВТОРЯЮТСЯ», — и общий ключ уронил бы
                    // список дублем.
                    val key = "${section.kind}-${row.entry.note.id}"
                    item(key = key) {
                        SwipeRevealRow(
                            key = key,
                            openKey = openKey,
                            onOpen = { openKey = it },
                            actionLabel = deleteLabel(row.entry.items.count { it.isAlive() }),
                            onAction = {
                                openKey = null
                                vm.deleteNote(row.entry.note.id)
                            },
                        ) { revealed ->
                            NoteRow(
                                row = row,
                                compact = revealed > 0.05f,
                                topicName = row.entry.note.topicId?.let { topicNames[it] },
                                section = section,
                                onClick = { if (openKey == null) onOpenNote(row.entry.note.id) },
                            )
                        }
                    }
                }
            }

            // Закрытые записи ушли из «всего» — но не в никуда, и строка внизу
            // говорит куда. Стоит один раз, за хайрлайном, после последнего
            // дня: под каждым днём она превратилась бы в шум.
            //
            // Тап переключает фильтр, а не разворачивает на месте: разворот
            // вернул бы в «всё» ровно ту кашу, которую версия убирает.
            if (filter == FeedView.Filter.ALL && closed > 0) {
                item(key = "closed-hidden") {
                    Column(Modifier.fillMaxWidth().padding(top = Space.ml)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Prinyal.colors.hairline)
                        )
                        MetaText(
                            text = stringResource(
                                R.string.feed_closed_hidden,
                                pluralStringResource(R.plurals.feed_closed_count, closed, closed),
                            ),
                            color = Prinyal.colors.inkFaint,
                            modifier = Modifier
                                .padding(horizontal = Space.screen)
                                .tap { vm.setFeedFilter(FeedView.Filter.DONE) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Пять слов фильтра (Д-25).
 *
 * Слова, а не чипы: пять слов влезают в строку целиком, а обводка и счётчики
 * сделали бы из них органы управления. Механика ровно та же, что у трёх
 * поверхностей в шапке, — человеку нечего изучать заново.
 */
@Composable
private fun FilterRow(current: FeedView.Filter, onPick: (FeedView.Filter) -> Unit) {
    val words = listOf(
        FeedView.Filter.ALL to R.string.filter_all,
        FeedView.Filter.PLANNED to R.string.filter_planned,
        FeedView.Filter.RETURNING to R.string.filter_returning,
        FeedView.Filter.DONE to R.string.filter_done,
        FeedView.Filter.BURIED to R.string.filter_buried,
    )
    // Пять слов обязаны влезть в строку целиком: скролла и обрезки здесь нет
    // по решению дизайнера. После того как кегль вырос, а у слов появилась
    // зона нажатия, фиксированный шаг между ними перестал помещаться —
    // «похоронено» вывалилось в вертикальный столбик по букве. Поэтому шаг
    // задаёт не константа, а сама строка: SpaceBetween раскладывает слова по
    // ширине, а зазор между ними даёт отступ зоны нажатия.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen - Touch.PAD_DP.dp)
            .padding(bottom = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        words.forEach { (filter, label) ->
            val active = filter == current
            Text(
                text = stringResource(label),
                // Golos, а не моно: так сказано в макете («пять слов Golos 14»),
                // и так они помещаются. Моноширинный набор шире почти вдвое —
                // после того как кегль вырос, «похоронено» стало обрезаться
                // краем экрана, а на телефоне владельца строка вообще не
                // помещалась: у него экран уже, чем у эмулятора.
                style = Prinyal.type.body.copy(fontSize = 15.sp),
                color = if (active) Prinyal.colors.ink else Prinyal.colors.inkFaint,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                // Слово не переносится ни при каких обстоятельствах: перенос по
                // буквам читается как поломка, а не как узкий экран.
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.tap { onPick(filter) },
            )
        }
    }
}

/** Пустой результат фильтра — что именно пусто, без кнопок (Д-25). */
@Composable
private fun FilteredEmpty(filter: FeedView.Filter) {
    val body = when (filter) {
        FeedView.Filter.PLANNED -> R.string.filter_empty_planned
        FeedView.Filter.RETURNING -> R.string.filter_empty_returning
        FeedView.Filter.DONE -> R.string.filter_empty_done
        else -> R.string.filter_empty_buried
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.screen, vertical = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(
            text = stringResource(R.string.topic_empty_title),
            style = Prinyal.type.itemTitle,
            color = Prinyal.colors.ink,
        )
        Text(
            text = stringResource(body),
            style = Prinyal.type.voice,
            color = Prinyal.colors.inkMuted,
        )
    }
}

/**
 * Разделитель дня (Д-24): дата один раз на сутки.
 *
 * В снимке от 19 августа «19 авг» повторялось семь раз — под каждой записью.
 * Дата ушла сюда, а в шапке записи осталось время; заодно появилась крупная
 * отбивка между сутками.
 */
@Composable
private fun DayHeader(section: FeedView.Section) {
    val text = when (section.kind) {
        FeedView.Section.Kind.TODAY ->
            stringResource(R.string.day_today, section.date?.let { Dates.dayFull(it) }.orEmpty())
        FeedView.Section.Kind.DAY -> section.date?.let { Dates.dayFull(it).uppercase() }.orEmpty()
        FeedView.Section.Kind.TOMORROW -> stringResource(R.string.day_tomorrow)
        FeedView.Section.Kind.THIS_WEEK -> stringResource(R.string.day_this_week)
        FeedView.Section.Kind.LATER -> stringResource(R.string.day_later)
        // Тот же элемент, что «ЗАВТРА» и «ПОЗЖЕ», — новых не заводим.
        FeedView.Section.Kind.REPEATING -> stringResource(R.string.repeat_group)
    }
    MetaText(
        text = text,
        color = Prinyal.colors.inkFaint,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen)
            .padding(top = Space.ml, bottom = Space.s),
    )
}

/** «Удалить» или «Удалить · 3 пункта» — число и есть предупреждение, модалки нет. */
@Composable
private fun deleteLabel(itemCount: Int): String =
    if (itemCount == 0) stringResource(R.string.note_delete)
    else pluralStringResource(R.plurals.note_delete_items, itemCount, itemCount)

@Composable
private fun Hairline() {
    HorizontalDivider(
        thickness = 1.dp,
        color = Prinyal.colors.hairline,
        modifier = Modifier.padding(horizontal = Space.screen),
    )
}

@Composable
private fun JunkSweepRow(count: Int, onSweep: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSweep)
            .padding(horizontal = Space.screen, vertical = Space.sm),
    ) {
        MetaText(
            text = pluralStringResource(R.plurals.feed_junk_sweep, count, count),
            color = Prinyal.colors.inkMuted,
        )
        MetaText(text = " · ", color = Prinyal.colors.inkFaint)
        MetaText(
            text = stringResource(R.string.feed_junk_sweep_action),
            color = Prinyal.colors.accentSelf,
        )
    }
}

/**
 * Запись в ленте (спека §2.1).
 *
 * Без пунктов — одна строка 48 dp: слева время, справа длительность и статус;
 * при открытой зоне свайпа длительность прячется (контент сжимается).
 * Разобранная — шапка «дата · N пунктов» и пункты с meta-строкой.
 */
@Composable
private fun NoteRow(
    row: FeedView.Row,
    compact: Boolean,
    topicName: String?,
    /** Где строка показана: под «ПОВТОРЯЮТСЯ» слова меняются. */
    section: FeedView.Section? = null,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val note = row.entry.note

    // Политика возврата — свойство записи, а не пункта (Д-24).
    //
    // Человек наговорил комок один раз, и продукт возвращается к нему один раз.
    // Раньше «в плане · просто сохраню» печаталось под каждым пунктом — в
    // снимке от 19 августа одиннадцать раз подряд, — и текст пунктов разбивался
    // служебным на каждом шагу. Под пунктом статус остаётся только там, где он
    // отличается от общего.
    // Под заголовком «ПОВТОРЯЮТСЯ» слова «в плане» не нужно — фильтр уже сказал.
    val bare = section?.kind == FeedView.Section.Kind.REPEATING
    val plans = row.shown.map { Phrases.plan(context, it, bare = bare) }
    // Общий статус — тот, что у большинства, а не единственный на всех. Если
    // требовать полного совпадения, то одна дата среди трёх «просто сохраню»
    // возвращает нас к статусу под каждым пунктом — то есть к тому, из-за чего
    // всё и затевалось.
    val commonPlan = plans
        // График повтора никогда не поднимается в общую строку записи: она
        // остаётся политикой обычных пунктов (макеты 10a). Повтор всегда несёт
        // свою строку под собой, даже если он в записи один.
        .filterIndexed { index, _ -> row.shown[index].repeatRule == null }
        .let { plain ->
            plain.groupingBy { it }.eachCount()
                .filterValues { it > 1 }
                .maxByOrNull { it.value }
                ?.key
                ?: plain.singleOrNull()
        }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.screen)
            // 32 против 12: границу записи держит воздух, а не рамка.
            .padding(bottom = Space.ml),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier.weight(1f, fill = false).padding(end = Space.s),
            ) {
                // Время без даты: дата ушла в разделитель дня.
                MetaText(Dates.time(note.createdAt))
                topicName?.let {
                    MetaText(it, color = Prinyal.colors.inkFaint, maxLines = 1)
                }
            }
            MetaText(text = headline(row, note, compact), color = Prinyal.colors.inkFaint)
        }

        if (row.shown.isNotEmpty()) {
            // Линия слева появляется только у многопунктовых записей — она
            // отвечает на вопрос «это одна мысль или три».
            val many = row.shown.size + row.restPlanned > 1
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = Space.s)) {
                // Линия слева появляется только у многопунктовых записей — она
                // отвечает на вопрос «это одна мысль или три». У одиночной
                // записи её нет: там нечего объединять.
                if (many) {
                    Box(
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Prinyal.colors.hairline)
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = if (many) Space.sm else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    row.shown.forEachIndexed { index, item ->
                        ItemLine(
                            item = item,
                            // Свой статус — только когда он отличается от общего.
                            // У повтора — всегда: общей строки у него не бывает.
                            showPlan = item.repeatRule != null ||
                                commonPlan == null || plans[index] != commonPlan,
                            plan = plans[index],
                        )
                    }
                }
            }
        }

        rest(row)?.let {
            MetaText(it, color = Prinyal.colors.inkFaint, modifier = Modifier.padding(top = Space.sm))
        }

        // Один статус на запись, внизу группы.
        if (commonPlan != null && row.shown.isNotEmpty()) {
            MetaText(
                text = commonPlan,
                color = Prinyal.colors.accentSelf,
                modifier = Modifier.padding(top = Space.sm),
            )
        }
    }
}

/** Правая часть шапки: «1 пункт», «1 из 4» под фильтром или состояние записи. */
@Composable
private fun headline(row: FeedView.Row, note: NoteEntity, compact: Boolean): String {
    val live = row.shown.size + row.restPlanned
    return when {
        // Закрытая запись — словом, а не цифрой: «1 сделано» без текста
        // читается как сбой, «всё сделано» — как состояние (Д-24).
        row.allClosed && row.restGone == 0 -> stringResource(R.string.note_all_done)
        row.allClosed && row.restDone == 0 -> stringResource(R.string.note_all_gone)
        row.allClosed -> stringResource(R.string.note_all_closed)
        row.filtered -> stringResource(R.string.note_matched, row.matched, row.total)
        live > 0 -> pluralStringResource(R.plurals.note_items_count, live, live)
        else -> {
            val status = statusLabel(NoteStatus.of(note.status))
            val duration = formatDuration(note.durationMs)
            if (compact || duration == null) status else "$duration $status"
        }
    }
}

/** Строка остатка: «ещё 1 в плане · 1 сделано». */
@Composable
private fun rest(row: FeedView.Row): String? {
    // У закрытой записи остатка нет: её состояние уже сказано в шапке одним
    // словом, и повторять «1 сделано» строкой ниже незачем.
    if (row.allClosed) return null
    val parts = buildList {
        if (row.restPlanned > 0) {
            add(pluralStringResource(R.plurals.note_rest_planned, row.restPlanned, row.restPlanned))
        }
        if (row.restDone > 0) add(stringResource(R.string.topic_summary_done, row.restDone))
        if (row.restGone > 0) add(stringResource(R.string.topic_summary_gone, row.restGone))
    }
    if (parts.isEmpty()) return null
    return if (row.restPlanned > 0) {
        stringResource(R.string.note_rest, parts.joinToString(" · "))
    } else {
        parts.joinToString(" · ")
    }
}

/** Пункт: текст и meta-строка сегментами через « · », пустые сегменты не печатаются. */
@Composable
private fun ItemLine(
    item: ItemEntity,
    showPlan: Boolean = true,
    /** Готовая строка плана: у повтора она зависит от места показа. */
    plan: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = ItemState.of(item.state)
    val closed = item.isClosed()

    Column(modifier.fillMaxWidth()) {
        Text(
            text = item.text,
            style = Prinyal.type.label,
            color = if (closed) Prinyal.colors.inkFaint else Prinyal.colors.ink,
            textDecoration = if (state == ItemState.DONE) TextDecoration.LineThrough else null,
        )
        val segments = buildList {
            // Адресат — только у «сказать»: у остальных типов он не звучал.
            if (ItemType.of(item.type) == ItemType.TELL) item.who?.let(::add)
            if (closed) add(stateLabel(state))
            if (!closed && showPlan) add(plan ?: Phrases.plan(context, item))
        }
        if (segments.isEmpty()) return@Column
        MetaText(
            text = segments.joinToString(" · "),
            color = if (state == ItemState.DONE) Prinyal.colors.done else Prinyal.colors.inkFaint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun EmptyFeed() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.feed_empty_title),
            style = Prinyal.type.itemTitle,
            color = Prinyal.colors.ink,
        )
        Text(
            text = stringResource(R.string.feed_empty_body),
            style = Prinyal.type.body,
            color = Prinyal.colors.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Space.sm),
        )
    }
}

// --- мелкая логика строк ---

private fun NoteWithItems.isJunk(): Boolean =
    items.isEmpty() && NoteStatus.of(note.status) in
        setOf(NoteStatus.FAILED_ASR, NoteStatus.FAILED_LLM)

private fun ItemEntity.isAlive(): Boolean =
    ItemState.of(state) in setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)

private fun ItemEntity.isClosed(): Boolean =
    ItemState.of(state) in setOf(ItemState.DONE, ItemState.DISMISSED, ItemState.EXPIRED)

@Composable
private fun statusLabel(status: NoteStatus): String = stringResource(
    when (status) {
        NoteStatus.RECORDED -> R.string.status_recorded
        NoteStatus.QUEUED -> R.string.status_queued
        NoteStatus.SENT -> R.string.status_sent
        NoteStatus.PARSED -> R.string.status_parsed
        NoteStatus.FAILED_ASR -> R.string.status_failed_asr
        NoteStatus.FAILED_LLM -> R.string.status_failed_llm
    }
)

@Composable
private fun stateLabel(state: ItemState): String = stringResource(
    when (state) {
        ItemState.PLANNED -> R.string.state_planned
        ItemState.RETURNED -> R.string.state_returned
        ItemState.DONE -> R.string.state_done
        ItemState.SNOOZED -> R.string.state_snoozed
        ItemState.DISMISSED -> R.string.state_dismissed
        ItemState.EXPIRED -> R.string.state_expired
    }
)

private fun formatTime(millis: Long): String = Dates.dayTime(millis)

private fun formatDuration(ms: Long): String? =
    if (ms <= 0) null else "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)


/**
 * Выдача поиска (Р-18.5, макет 11b): расшифровка целиком с подсветкой.
 *
 * 176 знаков — реплика, а не документ: человек ищет свои слова и видит их в
 * своей фразе. Закрытое стоит рядом с живым и не приглушено — приглушение
 * значит «сейчас не участвует», а в выдаче участвует всё.
 */
@Composable
private fun SearchResults(vm: AppViewModel, query: String, onOpenNote: (String) -> Unit) {
    val results by vm.searchResults.collectAsState()
    val topicNames by vm.topics.collectAsState()
    val names = remember(topicNames) { topicNames.associate { it.id to it.name } }
    val context = androidx.compose.ui.platform.LocalContext.current

    when {
        query.isBlank() -> SearchHintScreen(
            R.string.search_idle_title, R.string.search_idle_body,
        )

        results.isEmpty() -> SearchHintScreen(
            R.string.search_empty_title, R.string.search_empty_body,
        )

        else -> LazyColumn(contentPadding = PaddingValues(top = Space.xs, bottom = Space.xxl)) {
            item(key = "search-count") {
                MetaText(
                    text = pluralStringResource(
                        R.plurals.feed_summary_notes, results.size, results.size,
                    ),
                    color = Prinyal.colors.inkFaint,
                    modifier = Modifier
                        .padding(horizontal = Space.screen)
                        .padding(bottom = Space.s),
                )
            }
            items(results, key = { it.entry.note.id }) { result ->
                SearchRow(
                    result = result,
                    topicName = result.entry.note.topicId?.let { names[it] },
                    onClick = { onOpenNote(result.entry.note.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchRow(
    result: ai.prinim.prinyal.domain.NoteSearch.Result,
    topicName: String?,
    onClick: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val highlight = Prinyal.colors.accentSelfSoft
    val note = result.entry.note
    val transcript = note.transcript.orEmpty()

    // Состояние — справка, а не сигнал: словами, без зачёркивания и зелёного.
    val live = result.entry.items.count { ItemState.of(it.state) in setOf(
        ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED,
    ) }
    val gone = result.entry.items.count {
        ItemState.of(it.state) in setOf(ItemState.DISMISSED, ItemState.EXPIRED)
    }
    val stateWord = when {
        result.entry.items.isEmpty() -> stringResource(R.string.search_state_none)
        live > 0 -> "$live в плане"
        gone > 0 && gone == result.entry.items.size -> "всё похоронено"
        gone > 0 -> "$gone похоронено"
        else -> "всё сделано"
    }

    // Потолок — шесть строк, обрыв только после совпадения: слово, которое
    // искали, видно всегда (11b — единственный случай обрезки в выдаче).
    val annotated = remember(transcript, result.spans) {
        buildAnnotatedString {
            var cursor = 0
            result.spans.forEach { span ->
                if (span.first < cursor) return@forEach
                append(transcript.substring(cursor, span.first))
                withStyle(SpanStyle(background = highlight)) {
                    append(transcript.substring(span.first, span.last + 1))
                }
                cursor = span.last + 1
            }
            append(transcript.substring(cursor))
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.screen)
            .padding(bottom = Space.ml),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                MetaText(Dates.day(note.createdAt))
                topicName?.let { MetaText(it, color = Prinyal.colors.inkFaint, maxLines = 1) }
            }
            MetaText(stateWord, color = Prinyal.colors.inkFaint)
        }
        Text(
            text = annotated,
            style = Prinyal.type.body,
            color = Prinyal.colors.ink,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SearchHintScreen(title: Int, body: Int) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.xl)
            .padding(top = Space.xxl),
    ) {
        Text(
            text = stringResource(title),
            style = Prinyal.type.voice,
            color = Prinyal.colors.inkMuted,
        )
        Text(
            text = stringResource(body),
            style = Prinyal.type.voice,
            color = Prinyal.colors.inkFaint,
            modifier = Modifier.padding(top = Space.s),
        )
    }
}
