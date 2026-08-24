package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.tap
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
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
fun ItemDetail(
    item: ItemEntity,
    returns: List<ReturnEntity>,
    rawSpan: String?,
    onEdit: () -> Unit,
    onStopRepeat: () -> Unit,
    onRevive: () -> Unit = {},
    /** Все пункты записи закрыты: возврат вернёт и запись в ленту (11d). */
    noteAllClosed: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state = ItemState.of(item.state)
    // Закрытое неприкосновенно — это несущее правило сверки. Предлагать
    // править сделанное значит обещать то, чего продукт не сделает (Д-7).
    val closed = state in setOf(ItemState.DONE, ItemState.DISMISSED, ItemState.EXPIRED)

    // Раскрытие на месте, а не модалкой (аудит Д-7, п. 4).
    //
    // Модалка была первой в продукте: шесть версий обходились без диалогов, и
    // затемнение с карточкой по центру ломает пластику. Хуже другое — теряется
    // место: не видно, какой это пункт из четырёх и что вокруг. А раскрытие как
    // раз про «откуда это взялось».
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.m, top = Space.s, bottom = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        run {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                // Плана здесь нет намеренно. Раскрытие живёт **под** карточкой
                // пункта, а карточка ту же фразу уже напечатала строкой выше —
                // получалось «напомню утром» дважды подряд. Раскрытие отвечает
                // на вопрос «откуда это взялось», а не повторяет видимое.

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
                        // Заголовок появляется от двух событий: «Что с ним было»
                        // над единственным словом «сделано» — заголовок ради
                        // одного слова (аудит Д-7).
                        if (history.size > 1) {
                            MetaText(
                                stringResource(R.string.item_history),
                                color = Prinyal.colors.inkFaint,
                            )
                        }
                        history.forEach { line ->
                            MetaText(line, color = Prinyal.colors.inkMuted)
                        }
                    }
                }

                // Отмена повтора — не в ряду с «Поправить»: это другой род
                // действия. Приписка стоит **до** нажатия, а не в снекбаре
                // после: «Не повторять» ничего не удаляет, и цена действия
                // должна быть сказана заранее (макеты 10c).
                if (!closed && item.repeatRule != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Prinyal.colors.hairline)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(
                            text = stringResource(R.string.item_repeat_off),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.inkMuted,
                            softWrap = false,
                            modifier = Modifier.tap(onClick = onStopRepeat),
                        )
                        item.repeatNextAt?.let { next ->
                            MetaText(
                                stringResource(
                                    R.string.item_repeat_off_hint,
                                    Dates.whenWill(next),
                                ),
                                color = Prinyal.colors.inkFaint,
                            )
                        }
                    }
                }

                // «В план» (Р-18.4): стоит там, где у живого «Поправить», —
                // приглушено, не акцент: акцент значит «продукт сделал сам»,
                // а тут действие человека. Цена сказана до нажатия.
                if (closed && item.repeatRule == null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(
                            text = stringResource(R.string.item_revive),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.inkMuted,
                            softWrap = false,
                            modifier = Modifier.tap(onClick = onRevive),
                        )
                        MetaText(
                            stringResource(
                                if (noteAllClosed) R.string.item_revive_hint_note
                                else R.string.item_revive_hint
                            ),
                            color = Prinyal.colors.inkFaint,
                        )
                    }
                }

                // Единственный выход в изменение — явный и подписанный словом.
                //
                // Ряд растянут по ширине и не переносится: с появлением
                // «Не повторять» три слова перестали помещаться, и «Закрыть»
                // молча сложилось в столбик из букв — ровно как в фильтре
                // ленты после подъёма кегля (1.0.3). Перенос в ряду слов-кнопок
                // всегда ошибка, поэтому запрещаем его, а не подбираем отступы.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (!closed) {
                        Text(
                            text = stringResource(R.string.item_edit),
                            style = Prinyal.type.label,
                            color = Prinyal.colors.accentSelf,
                            softWrap = false,
                            modifier = Modifier.tap(onClick = onEdit),
                        )
                    }
                    Text(
                        text = stringResource(R.string.item_close),
                        style = Prinyal.type.label,
                        color = Prinyal.colors.inkMuted,
                        softWrap = false,
                        modifier = Modifier.tap(onClick = onDismiss),
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
    // История повтора устроена иначе, и это единственное его отличие в
    // раскрытии: она длинная. Три последних раза и итог строкой — ни полосок,
    // ни графика, ни «серии из шести» (макеты 10b).
    if (item.repeatRule != null) return repeatHistory(returns)

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
    // Возврат в план — событие, а не стирание (11e): «сделано» осталось выше,
    // под ним встало «вернул в план».
    item.revivedAt?.let { lines += "${Dates.day(it)} · вернул в план" }
    return lines
}

/** Сколько раз повтор срабатывал и чем каждый раз кончился. */
private fun repeatHistory(returns: List<ReturnEntity>): List<String> {
    val happened = returns.filter { it.firedAt != null }.sortedByDescending { it.firedAt }
    if (happened.isEmpty()) return emptyList()

    val lines = happened.take(REPEAT_HISTORY).map { entity ->
        val what = when (entity.action) {
            "done" -> "сделал"
            "later" -> "отложил"
            // Раз, на который человек не ответил, — часть правды о повторе:
            // именно из таких складывается вопрос «напоминать дальше?».
            null -> "не ответил"
            else -> "не ответил"
        }
        "${Dates.day(entity.firedAt!!)} · $what"
    }
    val since = happened.minOf { it.firedAt!! }
    return lines + "всего ${happened.size} ${times(happened.size)} с ${Dates.day(since)}"
}

/** Сколько раз печатается списком; остальное сворачивается в итог. */
private const val REPEAT_HISTORY = 3

private fun times(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "раз"
    else -> "раза"
}

private fun stamp(millis: Long?): String? = millis?.let { Dates.day(it) }

// Формат живёт в domain/Dates: точка у сокращения месяца снимается там.
