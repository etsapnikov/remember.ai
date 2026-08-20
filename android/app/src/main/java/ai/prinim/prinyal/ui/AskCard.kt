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
import androidx.compose.ui.unit.sp
import ai.prinim.prinyal.ui.theme.MetaText
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

    // Подложки нет (Д-30). Радиус с заливкой — та же форма, что у «Собрано» и
    // вечернего возврата, и вопрос читался как ещё одна запись, только чужая.
    // Осталась метка и хайрлайн: то же семейство, что «СОБРАНО» и «ЗА НЕДЕЛЮ»,
    // но здесь продукт не отдаёт сделанное, а просит.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen)
            .padding(top = Space.m, bottom = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        MetaText(stringResource(R.string.ask_label), color = Prinyal.colors.accentSelf)
        Text(
            text = stringResource(R.string.ask_question, name),
            // На шаг крупнее реплик в ленте: это обращение, а не пересказ.
            style = Prinyal.type.voice.copy(fontSize = 20.sp),
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
                // «Не надо» закрывает имя **навсегда**, и цена сказана рядом,
                // до нажатия: раньше она жила в снекбаре после, когда решение
                // уже принято.
                Text(
                    text = stringResource(R.string.ask_no),
                    style = Prinyal.type.label,
                    color = Prinyal.colors.inkMuted,
                    modifier = Modifier.tap(onClick = onDecline),
                )
                MetaText(
                    text = "· " + stringResource(R.string.ask_never),
                    color = Prinyal.colors.inkFaint,
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


/**
 * «Саня Иванов и Саша Иванов — это один человек?» (Д-30).
 *
 * Та же форма, что у доспроса, и та же квота: одна карточка-вопрос в ленте.
 * Ответ «разные» фиксируется навсегда — дубли остаются двумя строками, и
 * переспрашивать продукт не вправе.
 */
@Composable
fun MergeAskCard(
    first: String,
    second: String,
    onSame: () -> Unit,
    onApart: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen)
            .padding(top = Space.m, bottom = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        MetaText(stringResource(R.string.ask_label), color = Prinyal.colors.accentSelf)
        Text(
            text = stringResource(R.string.ask_same_question, first, second),
            style = Prinyal.type.voice.copy(fontSize = 20.sp),
            color = Prinyal.colors.ink,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.ml),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.ask_same_yes),
                style = Prinyal.type.label,
                color = Prinyal.colors.accentSelf,
                modifier = Modifier.tap(onClick = onSame),
            )
            Text(
                text = stringResource(R.string.ask_same_no),
                style = Prinyal.type.label,
                color = Prinyal.colors.inkMuted,
                modifier = Modifier.tap(onClick = onApart),
            )
            MetaText(
                text = "· " + stringResource(R.string.ask_never),
                color = Prinyal.colors.inkFaint,
            )
        }
    }
}
