package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.Confidence
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.components.TypeGlyph
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Карточка записи (PRD §F-7) и «доказательство понимания» внутри приложения (§F-5).
 *
 * Здесь же — восстановление истины: транскрипт-сырец и аудио доступны всегда, потому
 * что нормализованный моделью текст может врать в именах и числах (PRD §2.1).
 */
@Composable
fun NoteScreen(vm: AppViewModel, noteId: String, onBack: () -> Unit) {
    val entry by vm.note(noteId).collectAsState(initial = null)
    val note = entry?.note

    var editing by remember { mutableStateOf<ItemEntity?>(null) }
    var returns by remember { mutableStateOf<Map<String, List<ReturnEntity>>>(emptyMap()) }

    LaunchedEffect(entry?.items?.size) {
        returns = entry?.items.orEmpty().associate { it.id to vm.returnsFor(it.id) }
    }

    if (note == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(stringResource(R.string.feed_empty), style = Prinyal.type.body)
        }
        return
    }

    val status = NoteStatus.of(note.status)
    val context = LocalContext.current
    // Строку деградации берём до LazyColumn: внутри LazyListScope composable-вызовов нет.
    val degradedText = Phrases.degraded(context, note.degraded)
    val items = entry?.items.orEmpty()

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.ml),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { AudioRow(File(note.audioPath), note.durationMs) }

        degradedText?.let { text ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(text, style = Prinyal.type.voice, color = Prinyal.colors.accentSelf)
                    if (status == NoteStatus.FAILED_ASR || status == NoteStatus.FAILED_LLM) {
                        Text(
                            text = stringResource(R.string.action_retry),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.accentSelf,
                            modifier = Modifier.clickable { vm.reparse(noteId) },
                        )
                    }
                }
            }
        }

        if (items.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.note_items)) }
            items(items, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    history = returns[item.id].orEmpty(),
                    onDone = { vm.markDone(item.id) },
                    onDismiss = { vm.dismiss(item.id) },
                    onEdit = { editing = item },
                )
            }
        }

        note.transcript?.takeIf { it.isNotBlank() }?.let { transcript ->
            item { SectionTitle(stringResource(R.string.note_transcript)) }
            item { TranscriptBlock(transcript, items) }
        }

        item {
            Text(
                text = stringResource(R.string.note_reparse),
                style = Prinyal.type.label,
                color = Prinyal.colors.inkMuted,
                modifier = Modifier
                    .clickable { vm.reparse(noteId) }
                    .padding(vertical = Space.s),
            )
        }
    }

    editing?.let { item ->
        EditItemSheet(
            item = item,
            onDismiss = { editing = null },
            onSave = { text, type, window, clear ->
                vm.editItem(item.id, text, type, window, clear)
                editing = null
            },
            onBury = {
                vm.bury(item.id)
                editing = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    MetaText(text, color = Prinyal.colors.inkFaint, modifier = Modifier.padding(top = Space.s))
}

/** Пункт с планом. Тап — правка, «не надо» — один тап без диалогов (F-5). */
@Composable
private fun ItemCard(
    item: ItemEntity,
    history: List<ReturnEntity>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContextOf()
    val state = ItemState.of(item.state)
    val uncertain = Confidence.of(item.confidence) == Confidence.LOW

    Column(
        Modifier
            .fillMaxWidth()
            .background(Prinyal.colors.surface, Radius.sheet)
            .border(1.dp, Prinyal.colors.rule, Radius.sheet)
            .clickable(onClick = onEdit)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.Top) {
            TypeGlyph(ItemType.of(item.type))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    text = item.text,
                    style = Prinyal.type.itemTitle,
                    // Низкая уверенность — приглушённо: продукт не притворяется уверенным.
                    color = if (uncertain) Prinyal.colors.inkMuted else Prinyal.colors.ink,
                )
                item.who?.let { MetaText(it) }
                Text(
                    text = Phrases.plan(context, item),
                    style = Prinyal.type.voice,
                    color = Prinyal.colors.accentSelf,
                )
                if (uncertain) {
                    MetaText(Phrases.uncertainty(context, item).orEmpty())
                }
            }
        }

        if (history.isNotEmpty()) {
            val planned = stringResource(R.string.state_planned)
            MetaText(history.joinToString(" · ") { it.action ?: planned })
        }

        if (state !in setOf(ItemState.DONE, ItemState.DISMISSED)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                Text(
                    text = stringResource(R.string.action_done),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.done,
                    modifier = Modifier.clickable(onClick = onDone),
                )
                Text(
                    text = stringResource(R.string.action_dismiss),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.inkMuted,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

/**
 * Транскрипт-сырец с подсветкой `raw_span`: видно, из какого куска речи вырос пункт.
 * Это и доверие, и отладка промпта.
 */
@Composable
private fun TranscriptBlock(transcript: String, items: List<ItemEntity>) {
    val highlight = Prinyal.colors.accentSelfSoft
    val ink = Prinyal.colors.inkMuted

    val annotated: AnnotatedString = remember(transcript, items) {
        val spans = items.mapNotNull { it.rawSpan?.takeIf(String::isNotBlank) }
        buildAnnotatedString {
            var cursor = 0
            val lower = transcript.lowercase()
            val marks = spans.mapNotNull { span ->
                val at = lower.indexOf(span.lowercase())
                if (at >= 0) at to (at + span.length) else null
            }.sortedBy { it.first }

            marks.forEach { (start, end) ->
                if (start < cursor) return@forEach
                append(transcript.substring(cursor, start))
                withStyle(SpanStyle(background = highlight)) {
                    append(transcript.substring(start, end))
                }
                cursor = end
            }
            append(transcript.substring(cursor))
        }
    }

    Text(text = annotated, style = Prinyal.type.body, color = ink)
}

/** Плеер: два тапа от ленты до звука (приёмка F-7). */
@Composable
private fun AudioRow(file: File, durationMs: Long) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(if (playing) R.string.note_pause else R.string.note_play),
            style = Prinyal.type.label,
            color = if (file.exists()) Prinyal.colors.accentSelf else Prinyal.colors.inkFaint,
            modifier = Modifier.clickable(enabled = file.exists()) {
                runCatching {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        player.reset()
                        player.setDataSource(file.absolutePath)
                        player.prepare()
                        player.setOnCompletionListener { playing = false }
                        player.start()
                        playing = true
                    }
                }
            },
        )
        MetaText("%d:%02d".format(durationMs / 60_000, (durationMs / 1000) % 60))
    }
}

@Composable
private fun LocalContextOf() = LocalContext.current
