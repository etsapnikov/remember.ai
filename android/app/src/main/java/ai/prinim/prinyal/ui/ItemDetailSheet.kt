package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Раскрытие пункта — только чтение (Р-15.4, Д-8).
 *
 * Раньше тап по пункту открывал правку. Тап — самый дешёвый жест в интерфейсе,
 * и вешать на него изменение данных значит делать случайное касание способом
 * что-то испортить. Теперь тап показывает, а меняет — второй, явный жест.
 *
 * Здесь же история жизни пункта: когда создан, когда возвращался, сколько раз
 * переносился. Она отвечает на вопрос, который иначе задать некому, — «почему
 * это до сих пор висит».
 */
@Composable
fun ItemDetailSheet(
    item: ItemEntity,
    returns: List<ReturnEntity>,
    rawSpan: String?,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = ItemState.of(item.state)

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .background(Prinyal.colors.surface, Radius.control)
                .padding(Space.ml),
        ) {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Text(
                    text = item.text,
                    style = Prinyal.type.itemTitle,
                    color = Prinyal.colors.ink,
                )

                // Состояние и срок — одной строкой тем же языком, что в карточке.
                MetaText(
                    // Дату печатает сама фраза плана — второй раз не повторяем.
                    text = Phrases.plan(context, item),
                    color = Prinyal.colors.inkMuted,
                )

                rawSpan?.takeIf { it.isNotBlank() }?.let { span ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        MetaText(
                            stringResource(R.string.item_from_speech),
                            color = Prinyal.colors.inkFaint,
                        )
                        // Кусок речи, из которого пункт вырос: это и доверие,
                        // и способ увидеть, что модель поняла не то.
                        Text(
                            text = span,
                            style = Prinyal.type.body,
                            color = Prinyal.colors.inkMuted,
                        )
                    }
                }

                val history = history(item, returns, state)
                if (history.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        MetaText(
                            stringResource(R.string.item_history),
                            color = Prinyal.colors.inkFaint,
                        )
                        history.forEach { line ->
                            MetaText(line, color = Prinyal.colors.inkMuted)
                        }
                    }
                }

                // Единственный выход в изменение — явный и подписанный словом.
                Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                    Text(
                        text = stringResource(R.string.item_edit),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.accentSelf,
                        modifier = Modifier.clickable(onClick = onEdit),
                    )
                    Text(
                        text = stringResource(R.string.item_close),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.inkMuted,
                        modifier = Modifier.clickable(onClick = onDismiss),
                    )
                }
            }
        }
    }
}

/**
 * История жизни пункта: 3–5 строк служебной типографикой, без таймлайн-графики.
 *
 * Переносы считаем отдельно от показов: «возвращался четырежды» и «трижды
 * отложен» — разные факты, и второй объясняет первый.
 */
private fun history(
    item: ItemEntity,
    returns: List<ReturnEntity>,
    state: ItemState,
): List<String> {
    val lines = mutableListOf<String>()

    val fired = returns.count { it.firedAt != null }
    if (fired > 0) lines += "возвращался $fired ${times(fired)}"

    val snoozed = returns.count { it.action == "later" }
    if (snoozed > 0) lines += "отложен $snoozed ${times(snoozed)}"

    returns.mapNotNull { it.firedAt }.maxOrNull()?.let { last ->
        lines += "последний раз ${stamp(last)}"
    }

    when (state) {
        ItemState.DONE -> lines += "сделано"
        ItemState.DISMISSED -> lines += "отменено"
        ItemState.EXPIRED -> lines += "больше не возвращаюсь"
        else -> Unit
    }
    return lines
}

private fun times(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "раз"
    else -> "раза"
}

private fun stamp(millis: Long?): String? = millis?.let {
    DAY.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))
