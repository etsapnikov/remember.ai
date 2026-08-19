package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.TopicOverview
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * «Разделы» — третья поверхность (Д-1).
 *
 * Имя и счёт, больше ничего: ни иконок, ни цветных меток, ни кнопки «создать».
 * Раздел рождается из речи или из пикера на карточке — «впрок» его завести
 * нельзя, и это главное отличие от менеджера заметок.
 *
 * Пустых разделов в списке не бывает: запрос их не возвращает, потому что
 * раздела без заметок в продукте не существует.
 */
@Composable
fun TopicsScreen(vm: AppViewModel, onOpen: (String?, String) -> Unit) {
    val topics by vm.topics.collectAsState()
    val loose by vm.looseNotes.collectAsState()
    val decisions by vm.decisionCount.collectAsState()

    if (topics.isEmpty() && loose == 0 && decisions == 0) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(
                Modifier.padding(horizontal = Space.screen),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(
                    text = stringResource(R.string.topics_empty_title),
                    style = Prinyal.type.itemTitle,
                    color = Prinyal.colors.ink,
                )
                // Ни кнопки «создать раздел», ни объяснения правил: продукт
                // обещает разложить сам, а не просит настроить структуру.
                Text(
                    text = stringResource(R.string.topics_empty_body),
                    style = Prinyal.type.voice,
                    color = Prinyal.colors.inkMuted,
                )
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = Space.screen, end = Space.screen, top = Space.s, bottom = Space.xxl,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        // «Решения» — не раздел, а выборка (Р-15.10): решение о релизе остаётся
        // в «Работе», а здесь видна их хронология. Появляется, только когда
        // решения есть: пустая строка обещала бы содержимое, которого нет.
        if (decisions > 0) {
            item {
                val name = stringResource(R.string.topics_decisions)
                TopicRow(
                    name = name,
                    notes = decisions,
                    liveItems = 0,
                    countsLive = false,
                    onClick = { onOpen(AppViewModel.DECISIONS, name) },
                )
                HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
            }
        }

        items(topics, key = { it.id }) { topic ->
            TopicRow(
                name = topic.name,
                notes = topic.notes,
                liveItems = topic.liveItems,
                onClick = { onOpen(topic.id, topic.name) },
            )
            HorizontalDivider(thickness = 1.dp, color = Prinyal.colors.hairline)
        }

        // «Без раздела» — не раздел, а остаток: всегда внизу и приглушён.
        if (loose > 0) {
            item {
                TopicRow(
                    name = stringResource(R.string.topics_loose),
                    notes = loose,
                    liveItems = 0,
                    // Живые пункты здесь не считаются, поэтому и не заявляются:
                    // «всё закрыто» на этой строке было неправдой — заметка без
                    // раздела прекрасно может ждать своего часа.
                    countsLive = false,
                    muted = true,
                    onClick = { onOpen(null, "") },
                )
            }
        }
    }
}

@Composable
private fun TopicRow(
    name: String,
    notes: Int,
    liveItems: Int,
    countsLive: Boolean = true,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = Prinyal.type.itemTitle,
            color = if (muted) Prinyal.colors.inkMuted else Prinyal.colors.ink,
            // Имя раздела человек задаёт сам, и оно бывает длинным. В ряду со
            // SpaceBetween текст без ограничения ширины не переносится, а
            // наезжает на счётчик: ряд сжимает, а не переносит.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val notesText = pluralStringResource(R.plurals.topics_notes, notes, notes)
        MetaText(
            // «Живых» — про невыполненные пункты: слово уже есть в речи продукта
            // про возвраты, второго термина заводить незачем.
            // Ноль живых — это «всё закрыто», а не отсутствие данных: раньше
            // сегмент просто пропадал, и строка выглядела недосчитанной.
            text = if (!countsLive) {
                notesText
            } else if (liveItems > 0) {
                "$notesText · ${pluralStringResource(R.plurals.topics_live, liveItems, liveItems)}"
            } else {
                "$notesText · ${stringResource(R.string.topics_all_closed)}"
            },
            color = Prinyal.colors.inkFaint,
        )
    }
}
