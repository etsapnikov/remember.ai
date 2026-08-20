package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.tap
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource

/**
 * Карточка-вопрос о человеке (Д-5), верхняя позиция ленты.
 *
 * Вопрос — инициатива продукта, поэтому набран репликой и на подложке «сделал
 * сам». Две кнопки, и обе текстовые: залитая кнопка в продукте одна — ответ на
 * возврат, и отбирать у неё внимание ради любопытства нельзя.
 *
 * Ответ здесь же строкой, а не отдельным экраном: спрашиваем одно слово
 * («сестра»), и гонять ради него человека на экран захвата — несоразмерно.
 */
@Composable
fun AskCard(
    name: String,
    onAnswer: (String) -> Unit,
    onDecline: () -> Unit,
) {
    var answering by remember(name) { mutableStateOf(false) }
    var draft by remember(name) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Prinyal.colors.accentSelfSoft, Radius.control)
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = stringResource(R.string.ask_question, name),
            style = Prinyal.type.voice,
            color = Prinyal.colors.ink,
        )

        if (!answering) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                Text(
                    text = stringResource(R.string.ask_tell),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier.tap { answering = true },
                )
                // «Не надо» — молча и навсегда для этого имени.
                Text(
                    text = stringResource(R.string.ask_no),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.inkMuted,
                    modifier = Modifier.tap(onClick = onDecline),
                )
            }
        } else {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = Prinyal.type.body.copy(color = Prinyal.colors.ink),
                cursorBrush = SolidColor(Prinyal.colors.accentSelf),
                modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
            )
            Text(
                text = stringResource(R.string.ask_save),
                style = Prinyal.type.label,
                color = Prinyal.colors.accentSelf,
                modifier = Modifier.tap { if (draft.isNotBlank()) onAnswer(draft.trim()) },
            )
        }
    }
}
