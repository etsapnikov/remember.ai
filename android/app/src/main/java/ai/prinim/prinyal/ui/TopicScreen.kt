package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteWithItems
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import ai.prinim.prinyal.domain.FeedView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Экран одного раздела (Д-1).
 *
 * Язык ленты, а не новый: первые слова заметки как заголовок, сводка пунктов по
 * состояниям, дата. Ни сортировки, ни поиска, ни «выбрать несколько» — раздел
 * это выборка, и читается он сверху вниз ровно как лента.
 */
@Composable
fun TopicScreen(
    vm: AppViewModel,
    topicId: String?,
    onOpenNote: (String) -> Unit,
    onPickPack: (String?, String) -> Unit = { _, _ -> },
) {
    val notes by vm.notesOf(topicId).collectAsState(initial = emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current
    // Заголовок пака — имя раздела: там падеж правильный по определению, в
    // отличие от темы, названной голосом (Р-15.13).
    val loose = stringResource(R.string.topics_loose)
    var title by androidx.compose.runtime.remember(topicId) {
        androidx.compose.runtime.mutableStateOf(loose)
    }
    androidx.compose.runtime.LaunchedEffect(topicId) {
        if (topicId != null) {
            title = vm.liveTopics().firstOrNull { it.id == topicId }?.name ?: loose
        }
    }

    val filter by vm.topicFilter.collectAsState()
    val sections = androidx.compose.runtime.remember(notes, filter) {
        FeedView.sections(notes, filter)
    }

    // Всё живёт в одной колонке.
    //
    // Раньше кнопка пака и список были **соседями без родителя**: composable
    // отдавал два элемента в чужой Box, LazyColumn с fillMaxSize ложился
    // поверх кнопки и забирал себе касания. Кнопка была видна, не нажималась и
    // висела на месте при скролле — ровно то, что владелец и описал.
    Column(Modifier.fillMaxSize()) {
        // Фильтр тот же, что в ленте: раздел — это та же лента, только уже.
        if (topicId != AppViewModel.DECISIONS) {
            TopicFilterRow(filter, onPick = { vm.setTopicFilter(it) })
        }

        // Контекст-пак (Р-15.13): всё, что известно по разделу, одним файлом.
        if (topicId != AppViewModel.DECISIONS) {
            PackButton(topicId, title, onPickPack)
        }

        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                MetaText(stringResource(R.string.feed_empty))
            }
            return@Column
        }
        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                MetaText(stringResource(R.string.topic_empty_title))
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.flatMap { it.rows }.forEachIndexed { index, row ->
                item(key = row.entry.note.id) {
                    TopicNoteRow(row.entry, onClick = { onOpenNote(row.entry.note.id) })
                    HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
                }
            }
        }
    }
}

/** Те же пять слов, что в ленте: раздел — та же лента, только уже. */
@Composable
private fun TopicFilterRow(current: FeedView.Filter, onPick: (FeedView.Filter) -> Unit) {
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

@Composable
private fun TopicNoteRow(entry: NoteWithItems, onClick: () -> Unit) {
    val context = LocalContext.current
    val title = entry.items.firstOrNull()?.text
        ?: entry.note.transcript?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.feed_empty)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = title,
            style = Prinyal.type.itemTitle,
            color = Prinyal.colors.ink,
            // Две строки: в одну заголовок обрезался посреди мысли — «выпилить
            // из настроек пункт с р…» опознать невозможно (аудит Д-7).
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Сводка растёт с числом состояний: «3 в плане · 1 сделано ·
            // 2 закрыто» уже упирается в дату. Жмётся сводка, дата остаётся
            // целой — она короткая и по ней ищут.
            MetaText(
                summary(context, entry),
                color = Prinyal.colors.inkFaint,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            MetaText(shortDate(entry.note.createdAt), color = Prinyal.colors.inkFaint)
        }
    }
}

/** Дата без времени: в разделе важен день, а не минута. */
private fun shortDate(millis: Long): String = Dates.day(millis)

/**
 * Сводка пунктов по состояниям — «3 в плане · 1 сделано».
 *
 * Считаем только то, что человека касается: похороненное и отменённое сливаем в
 * одно, потому что для него это одно и то же — «сюда больше не вернусь».
 */
@Composable
private fun summary(context: android.content.Context, entry: NoteWithItems): String {
    // Решение и факт делами не считаются: они не «в плане», их не делают.
    // Иначе раздел «Решения» показывал «1 в плане» под принятым решением —
    // продукт обещал вернуться к тому, к чему возвращаться не собирается.
    val live = entry.items.filterNot {
        ItemType.of(it.type) in setOf(ItemType.DECISION, ItemType.FACT)
    }
    val planned = live.count {
        ItemState.of(it.state) in setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)
    }
    val done = live.count { ItemState.of(it.state) == ItemState.DONE }
    val gone = live.count {
        ItemState.of(it.state) in setOf(ItemState.DISMISSED, ItemState.EXPIRED)
    }

    val parts = buildList {
        if (planned > 0) add(context.getString(R.string.topic_summary_planned, planned))
        if (done > 0) add(context.getString(R.string.topic_summary_done, done))
        if (gone > 0) add(context.getString(R.string.topic_summary_gone, gone))
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.topic_summary_empty) }
}


/**
 * «Собрать контекст» — файл уходит в системный «Поделиться».
 *
 * Копирования отдельной кнопкой нет: системный лист умеет и то, и другое, а
 * две кнопки рядом заставляли бы выбирать до того, как человек увидел файл.
 */
@Composable
private fun PackButton(
    topicId: String?,
    title: String,
    onPickPack: (String?, String) -> Unit,
) {
    val name = title
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.screen)
                .padding(top = Space.xs, bottom = Space.sm),
        ) {
            MetaText(
                text = stringResource(R.string.pack_build),
                color = Prinyal.colors.accentSelf,
                // Кнопка больше не собирает молча: открывает выбор записей
                // (Д-28). Раньше отсюда уходил весь раздел целиком.
                modifier = Modifier.tap { onPickPack(topicId, name) },
            )
        }
        // Черта отделяет действие от списка: без неё «Собрать контекст»
        // прилипало к первой заметке и читалось как её часть.
        HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
    }
}

fun sharePack(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.files", file,
    )
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_TITLE, file.name)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(send, null)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
