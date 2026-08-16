package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Replacements
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog

/**
 * Шторка нового правила словаря (Д-2).
 *
 * Три ступени сверху вниз: что слышу — что писать — как получится. Предпросмотр
 * на живой строке транскрипта, а не на абстрактном примере: человек должен
 * увидеть свою фразу починенной.
 *
 * Кнопка одна. «Применить один раз» здесь нет намеренно — это словарь, а для
 * разового случая уже есть «Поправить».
 */
@Composable
fun DictionarySheet(
    source: String,
    context: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(source) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .background(Prinyal.colors.surface, Radius.control)
                .padding(Space.ml),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    MetaText(stringResource(R.string.dict_from), color = Prinyal.colors.inkFaint)
                    Text(source, style = Prinyal.type.itemTitle, color = Prinyal.colors.ink)
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    MetaText(stringResource(R.string.dict_to), color = Prinyal.colors.inkFaint)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = Prinyal.type.itemTitle.copy(color = Prinyal.colors.ink),
                        cursorBrush = SolidColor(Prinyal.colors.accentSelf),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    MetaText(stringResource(R.string.dict_preview), color = Prinyal.colors.inkFaint)
                    Text(
                        // Предпросмотр считаем тем же кодом, что и боевую замену:
                        // разойдись они — человек увидел бы одно, а получил другое.
                        text = preview(context, source, draft),
                        style = Prinyal.type.body,
                        color = Prinyal.colors.inkMuted,
                    )
                }

                Box(
                    Modifier
                        .background(Prinyal.colors.accentSelf, Radius.pill)
                        .clickable { if (draft.isNotBlank()) onSave(draft.trim()) }
                        .padding(horizontal = Space.ml, vertical = Space.sm),
                ) {
                    Text(
                        text = stringResource(R.string.dict_save),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.paper,
                    )
                }
            }
        }
    }
}

private fun preview(context: String, from: String, to: String): String {
    if (to.isBlank()) return context
    val rule = ai.prinim.prinyal.data.ReplacementEntity(
        id = "preview",
        fromPhrase = from,
        fromNorm = Replacements.norm(from),
        toPhrase = to,
        createdAt = 0,
    )
    return Replacements.apply(context, listOf(rule)).text
}
