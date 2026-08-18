package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.NoteWithItems
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.components.SwipeRevealRow
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
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
    val notes by vm.feed.collectAsState()
    val llmEnabled by vm.llmEnabled.collectAsState()
    val ask by vm.askCandidate.collectAsState()
    // Имена разделов для строки заметки: раздел виден там же, где время (Р-15.3).
    val topics by vm.topics.collectAsState()
    val topicNames = remember(topics) { topics.associate { it.id to it.name } }

    // Отсчёт трёх дней тишины идёт с показа, а не с ответа: увидел — значит
    // спросили, даже если человек прошёл мимо.
    LaunchedEffect(ask?.id) { ask?.let { vm.markAsked(it.id) } }
    val listState = rememberLazyListState()

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
                    modifier = Modifier.clickable { vm.setLlmEnabled(true) },
                )
            }
        }

        if (notes.isEmpty()) {
            EmptyFeed()
            return@Column
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = Space.xs, bottom = Space.xxl),
        ) {
            // Групповая уборка: появляется при ≥ 3 записях без пунктов.
            if (junk.size >= 3) {
                item(key = "junk-sweep") {
                    JunkSweepRow(count = junk.size, onSweep = { vm.sweepJunk() })
                    Hairline()
                }
            }

            notes.forEachIndexed { index, entry ->
                item(key = entry.note.id) {
                    SwipeRevealRow(
                        key = entry.note.id,
                        openKey = openKey,
                        onOpen = { openKey = it },
                        actionLabel = deleteLabel(entry.items.count { it.isAlive() }),
                        onAction = {
                            openKey = null
                            vm.deleteNote(entry.note.id)
                        },
                    ) { revealed ->
                        NoteRow(
                            entry = entry,
                            compact = revealed > 0.05f,
                            topicName = entry.note.topicId?.let { topicNames[it] },
                            onClick = { if (openKey == null) onOpenNote(entry.note.id) },
                        )
                    }
                    if (index != notes.lastIndex) Hairline()
                }
            }
        }
    }
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
    entry: NoteWithItems,
    compact: Boolean,
    topicName: String?,
    onClick: () -> Unit,
) {
    val note = entry.note
    // Живые пункты — текстом, закрытые — сводкой одной строкой (аудит Д-7, п. 3).
    //
    // Зачёркивание придумано для строк списка покупок, где человек вычёркивает
    // сам и видит свой прогресс. У обычного пункта роль другая: сделанное
    // больше не требует внимания, а зачёркнутым жирным оно было самым заметным
    // на экране. Плюс «сделано» повторялось пять раз подряд — то же слово, тот
    // же цвет, ноль новой информации.
    val living = entry.items.filter { it.isAlive() }
    val closedItems = entry.items.filter { it.isClosed() }
    val alive = entry.items.filter { it.isAlive() || it.isClosed() }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.screen, vertical = Space.s),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (alive.isEmpty()) 32.dp else 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                MetaText(formatTime(note.createdAt))
                // «Без раздела» не подписываем: тишина вместо шума (Д-10).
                // Лента — время, «Разделы» — структура; чип в строке смешал бы
                // две системы, поэтому здесь имя тихим текстом, а не пилюлей.
                // Meta-цветом, а не акцентом: в ленте раздел читают, а не правят,
                // и колонка оранжевого была самым заметным на экране (аудит п. 9).
                // Акцент остаётся чипу в карточке — там раздел меняют.
                topicName?.let { MetaText(it, color = Prinyal.colors.inkFaint) }
            }
            if (alive.isEmpty()) {
                // «0:04 не расслышал»; длительность прячется, когда строка сжата.
                val status = statusLabel(NoteStatus.of(note.status))
                val duration = formatDuration(note.durationMs)
                MetaText(
                    text = if (compact || duration == null) status else "$duration $status",
                    color = Prinyal.colors.inkFaint,
                )
            } else {
                MetaText(pluralStringResource(R.plurals.note_items_count, alive.size, alive.size))
            }
        }

        living.forEach { item ->
            ItemLine(item, Modifier.padding(top = Space.sm))
        }

        // Сводка ровно та же, что на экране раздела: одна запись обязана
        // выглядеть одинаково в двух местах.
        if (closedItems.isNotEmpty()) {
            val done = closedItems.count { ItemState.of(it.state) == ItemState.DONE }
            val gone = closedItems.size - done
            val parts = buildList {
                if (done > 0) add(stringResource(R.string.topic_summary_done, done))
                if (gone > 0) add(stringResource(R.string.topic_summary_gone, gone))
            }
            MetaText(
                text = parts.joinToString(" · "),
                color = Prinyal.colors.inkFaint,
                modifier = Modifier.padding(top = Space.sm),
            )
        }
    }
}

/** Пункт: текст и meta-строка сегментами через « · », пустые сегменты не печатаются. */
@Composable
private fun ItemLine(item: ItemEntity, modifier: Modifier = Modifier) {
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
            add(stateLabel(state))
            if (!closed) add(Phrases.plan(context, item))
        }
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

private val DAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale("ru"))

private fun formatTime(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(DAY_TIME)

private fun formatDuration(ms: Long): String? =
    if (ms <= 0) null else "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
