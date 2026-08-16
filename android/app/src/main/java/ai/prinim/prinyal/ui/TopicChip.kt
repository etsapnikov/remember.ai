package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.TopicEntity
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Чип раздела у шапки карточки (Д-1).
 *
 * Источник различается **только цветом**, без подписей «авто»: акцент — отнёс
 * продукт, нейтральный — отнёс человек. Значка замка у ручного нет намеренно:
 * заморозка не событие, о котором надо рассказывать.
 */
@Composable
fun TopicChip(name: String, source: TopicSource, onClick: () -> Unit) {
    val machine = source == TopicSource.LLM
    Box(
        Modifier
            .border(
                1.dp,
                if (machine) Prinyal.colors.accentSelf else Prinyal.colors.rule,
                Radius.pill,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.xs),
    ) {
        MetaText(
            text = name,
            color = if (machine) Prinyal.colors.accentSelf else Prinyal.colors.inkMuted,
        )
    }
}

/**
 * Пикер «Отнести к…».
 *
 * Закрывается сразу после выбора — ни «Сохранить», ни квитанции: правка топика
 * утилитарна, праздновать нечего. «новый…» заводит раздел вместе с отнесением;
 * создать раздел впрок в продукте нельзя.
 */
@Composable
fun TopicPicker(
    topics: List<TopicEntity>,
    currentId: String?,
    onPick: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .background(Prinyal.colors.surface, Radius.control)
                .padding(Space.ml),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                MetaText(stringResource(R.string.topic_pick), color = Prinyal.colors.inkFaint)

                topics.forEach { topic ->
                    Row(
                        label = topic.name,
                        hint = if (topic.id == currentId) {
                            stringResource(R.string.topic_pick_here)
                        } else {
                            null
                        },
                        onClick = { onPick(topic.id) },
                    )
                }

                // «Без раздела» — законный выбор: человек вправе снять чужое решение.
                Row(
                    label = stringResource(R.string.topics_loose),
                    hint = if (currentId == null) stringResource(R.string.topic_pick_here) else null,
                    onClick = { onPick(null) },
                )

                if (!creating) {
                    Row(
                        label = stringResource(R.string.topic_pick_new),
                        hint = null,
                        accent = true,
                        onClick = { creating = true },
                    )
                } else {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = Prinyal.type.body.copy(color = Prinyal.colors.ink),
                        cursorBrush = SolidColor(Prinyal.colors.accentSelf),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .padding(vertical = Space.s),
                    )
                    Row(
                        label = stringResource(R.string.topic_pick_new),
                        hint = null,
                        accent = true,
                        onClick = { if (draft.isNotBlank()) onCreate(draft.trim()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Row(label: String, hint: String?, accent: Boolean = false, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.s),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = Prinyal.type.body,
            color = if (accent) Prinyal.colors.accentSelf else Prinyal.colors.ink,
        )
        hint?.let { MetaText(it, color = Prinyal.colors.inkFaint) }
    }
}
