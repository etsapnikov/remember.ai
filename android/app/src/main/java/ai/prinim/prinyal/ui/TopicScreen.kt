package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.NoteWithItems
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Экран одного раздела (Д-1).
 *
 * Язык ленты, а не новый: первые слова заметки как заголовок, сводка пунктов по
 * состояниям, дата. Ни сортировки, ни поиска, ни «выбрать несколько» — раздел
 * это выборка, и читается он сверху вниз ровно как лента.
 */
@Composable
fun TopicScreen(vm: AppViewModel, topicId: String?, onOpenNote: (String) -> Unit) {
    val notes by vm.notesOf(topicId).collectAsState(initial = emptyList())

    if (notes.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            MetaText(stringResource(R.string.feed_empty))
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(notes, key = { it.note.id }) { entry ->
            TopicNoteRow(entry, onClick = { onOpenNote(entry.note.id) })
            HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MetaText(summary(context, entry), color = Prinyal.colors.inkFaint)
            MetaText(shortDate(entry.note.createdAt), color = Prinyal.colors.inkFaint)
        }
    }
}

/** Дата без времени: в разделе важен день, а не минута. */
private val DAY: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale("ru"))

private fun shortDate(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(DAY)

/**
 * Сводка пунктов по состояниям — «3 в плане · 1 сделано».
 *
 * Считаем только то, что человека касается: похороненное и отменённое сливаем в
 * одно, потому что для него это одно и то же — «сюда больше не вернусь».
 */
@Composable
private fun summary(context: android.content.Context, entry: NoteWithItems): String {
    val planned = entry.items.count {
        ItemState.of(it.state) in setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)
    }
    val done = entry.items.count { ItemState.of(it.state) == ItemState.DONE }
    val gone = entry.items.count {
        ItemState.of(it.state) in setOf(ItemState.DISMISSED, ItemState.EXPIRED)
    }

    val parts = buildList {
        if (planned > 0) add(context.getString(R.string.topic_summary_planned, planned))
        if (done > 0) add(context.getString(R.string.topic_summary_done, done))
        if (gone > 0) add(context.getString(R.string.topic_summary_gone, gone))
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.topic_summary_empty) }
}
