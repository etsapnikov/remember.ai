package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.NoteWithItems
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.components.TypeGlyph
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Лента (PRD §F-7). Хронология записей со статусами пунктов.
 *
 * Ни фильтров, ни папок, ни сортировки: любой элемент организации, требующий решения
 * от пользователя, делает продукт системой, которую надо вести (ТЗ UI §1).
 */
@Composable
fun FeedScreen(vm: AppViewModel, onOpenNote: (String) -> Unit) {
    val notes by vm.feed.collectAsState()
    val pending by vm.pendingCount.collectAsState()

    Column(Modifier.fillMaxSize()) {
        if (pending > 0) {
            // Оффлайн — не ошибка, а состояние: бейдж без драмы (ТЗ UI §3.2).
            // Но и врать нельзя: «нет связи» только если связь действительно рвалась,
            // иначе это просто «ещё не разобрал».
            val stuck = notes.any { it.note.degraded in NO_SERVER_CODES }
            MetaText(
                text = stringResource(
                    if (stuck) R.string.feed_offline_badge else R.string.feed_pending
                ),
                color = Prinyal.colors.inkMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Prinyal.colors.wellSurface)
                    .padding(horizontal = Space.screen, vertical = Space.s),
            )
        }

        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(
                    text = stringResource(R.string.feed_empty),
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkFaint,
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Space.screen,
                end = Space.screen,
                top = Space.s,
                bottom = Space.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Space.itemGap),
        ) {
            items(notes, key = { it.note.id }) { entry ->
                NoteRow(entry, onClick = { onOpenNote(entry.note.id) })
            }
        }
    }
}

@Composable
private fun NoteRow(entry: NoteWithItems, onClick: () -> Unit) {
    val note = entry.note
    val status = NoteStatus.of(note.status)

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetaText(formatTime(note.createdAt))
            MetaText(
                text = statusLabel(status),
                color = when (status) {
                    NoteStatus.FAILED_ASR, NoteStatus.FAILED_LLM -> Prinyal.colors.inkMuted
                    NoteStatus.PARSED -> Prinyal.colors.inkFaint
                    else -> Prinyal.colors.accentSelf
                },
            )
        }

        // Пока разбора нет, показываем услышанное. Дублировать статус словом
        // «записано» незачем — он уже написан справа.
        val preview = note.transcript.orEmpty()
        if (entry.items.isEmpty()) {
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkMuted,
                    maxLines = 2,
                )
            }
        } else {
            entry.items.forEach { item -> ItemLine(item) }
        }
    }
}

@Composable
private fun ItemLine(item: ItemEntity) {
    val state = ItemState.of(item.state)
    val closed = state in setOf(ItemState.DONE, ItemState.DISMISSED, ItemState.EXPIRED)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.Top,
    ) {
        TypeGlyph(
            type = ItemType.of(item.type),
            color = if (closed) Prinyal.colors.inkFaint else Prinyal.colors.ink,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                text = item.text,
                style = Prinyal.type.body,
                color = if (closed) Prinyal.colors.inkFaint else Prinyal.colors.ink,
                // Похороненное не вычёркивается красным крестом — просто гаснет
                // (ТЗ UI §3.6: без вины и без драмы).
                textDecoration = if (state == ItemState.DONE) TextDecoration.LineThrough else null,
            )
            MetaText(
                text = stateLabel(state),
                color = if (state == ItemState.DONE) Prinyal.colors.done else Prinyal.colors.inkFaint,
            )
        }
    }
}

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

private val NO_SERVER_CODES = setOf("no_server", "not_configured")

private val DAY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM · HH:mm", java.util.Locale("ru"))

private fun formatTime(millis: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(DAY_TIME)
