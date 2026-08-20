package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.domain.FeedView
import ai.prinim.prinyal.domain.LinkCandidates
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import ai.prinim.prinyal.ui.theme.Touch
import ai.prinim.prinyal.ui.theme.tap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Что взять в пак (Д-28).
 *
 * **Отметка — приглушением, а не галочкой.** Решение дизайнера, и оно про
 * систему, а не про экран: галочка потянула бы чекбоксы во весь продукт, где
 * их нет нигде, а способ сказать «это сейчас не участвует» в языке уже был —
 * им набраны пункты во время правки транскрипта. Отмеченная запись читается
 * обычным цветом, снятая приглушена целиком, тап переключает. Ни одного органа
 * управления, которого человек раньше не видел.
 *
 * Фильтр меняет, что видно; отметки живут поверх и переживают смену фильтра.
 * «Собрать» собирает отмеченное **целиком**, а не только видимое, — иначе
 * фильтр молча урезал бы пак, а это ровно та болезнь, которую лечим.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PackPickScreen(vm: AppViewModel, onShare: (java.io.File) -> Unit) {
    val draft by vm.packDraft.collectAsState()
    val current = draft ?: return
    var filter by remember { mutableStateOf(FeedView.Filter.ALL) }

    // Одна запись — собирать нечего: пак выйдет короче самой записи.
    if (current.notes.size < 2) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = Space.screen, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                text = stringResource(R.string.empty_pick_title),
                style = Prinyal.type.itemTitle,
                color = Prinyal.colors.ink,
            )
            Text(
                text = stringResource(R.string.empty_pick_body),
                style = Prinyal.type.voice,
                color = Prinyal.colors.inkMuted,
            )
        }
        return
    }

    val visible = remember(current.notes, filter) {
        FeedView.sections(current.notes, filter).flatMap { it.rows }.map { it.entry }
    }

    Column(Modifier.fillMaxSize()) {
        // «Похоронено» здесь нет: похоронённое в пак не идёт.
        PickFilter(filter, onPick = { filter = it })

        MetaText(
            text = when {
                current.picked.isEmpty() -> stringResource(R.string.pack_pick_none)
                current.picked.size == current.notes.size ->
                    stringResource(R.string.pack_pick_all, current.notes.size)
                else -> stringResource(
                    R.string.pack_pick_some, current.picked.size, current.notes.size,
                )
            },
            color = Prinyal.colors.inkFaint,
            modifier = Modifier.padding(horizontal = Space.screen, vertical = Space.xs),
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Space.screen, end = Space.screen, bottom = Space.m,
            ),
        ) {
            items(visible, key = { it.note.id }) { entry ->
                val on = entry.note.id in current.picked
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { vm.togglePacked(entry.note.id) },
                            onLongClick = { vm.packOnly(entry.note.id) },
                        )
                        .padding(vertical = Space.sm),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = LinkCandidates.opening(entry.note.transcript),
                        style = Prinyal.type.label,
                        // Снятая запись приглушена целиком, вместе с meta-строкой:
                        // видно, что уйдёт в пак, не читая ни одной галочки.
                        color = if (on) Prinyal.colors.ink else Prinyal.colors.inkFaint,
                    )
                    val items = entry.items.size
                    MetaText(
                        text = Dates.day(entry.note.createdAt) +
                            if (items > 0) {
                                " · " + pluralStringResource(R.plurals.note_items_count, items, items)
                            } else {
                                ""
                            },
                        color = if (on) Prinyal.colors.inkFaint else Prinyal.colors.hairline,
                    )
                }
            }
        }

        val ready = current.picked.isNotEmpty()
        val context = LocalContext.current
        Box(
            Modifier
                .fillMaxWidth()
                // Отступ на системную навигацию: без него кнопка уезжала под
                // наэкранные клавиши и была наполовину не видна и не нажимаема.
                // Экран единственный в продукте с действием, прибитым к низу, —
                // остальные кончаются списком, и врезка им не нужна.
                .navigationBarsPadding()
                .padding(horizontal = Space.screen)
                .padding(bottom = Space.ml),
        ) {
            Box(
                Modifier
                    // Заливка означает «есть что делать»: без отметок она уходит.
                    .background(
                        if (ready) Prinyal.colors.accentSelf else Prinyal.colors.wellSurface,
                        Radius.pill,
                    )
                    .tap(enabled = ready) { vm.buildPack(onShare) }
                    .padding(horizontal = Space.ml, vertical = Space.s),
            ) {
                Text(
                    text = stringResource(R.string.pack_pick_go),
                    style = Prinyal.type.label,
                    color = if (ready) Prinyal.colors.paper else Prinyal.colors.inkFaint,
                )
            }
        }
    }
}

/** Четыре слова: «похоронено» в пак не идёт. */
@Composable
private fun PickFilter(current: FeedView.Filter, onPick: (FeedView.Filter) -> Unit) {
    val words = listOf(
        FeedView.Filter.ALL to R.string.filter_all,
        FeedView.Filter.PLANNED to R.string.filter_planned,
        FeedView.Filter.RETURNING to R.string.filter_returning,
        FeedView.Filter.DONE to R.string.filter_done,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen - Touch.PAD_DP.dp)
            .padding(bottom = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        words.forEach { (filter, label) ->
            val active = filter == current
            Text(
                text = stringResource(label),
                style = Prinyal.type.body.copy(fontSize = 15.sp),
                color = if (active) Prinyal.colors.ink else Prinyal.colors.inkFaint,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.tap { onPick(filter) },
            )
        }
    }
}
