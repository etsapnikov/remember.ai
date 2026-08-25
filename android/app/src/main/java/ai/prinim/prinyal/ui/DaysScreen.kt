package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.DayEntity
import ai.prinim.prinyal.domain.Dates
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import ai.prinim.prinyal.ui.theme.tap
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate

/**
 * «Дни» (Р-18.1, макет 12c): вечерние ответы, по строке на день.
 *
 * Оглавление недели, а не таблица: дата колонкой слева, впечатление рядом.
 * Пропущенных строк нет — прочерк был бы укором без слов, а укоров в продукте
 * нет ни одного; разрывы в датах видны и так.
 *
 * Свайпа и правки нет: день — это память, а не дело.
 */
@Composable
fun DaysScreen(vm: AppViewModel) {
    val days by vm.days.collectAsState()
    var opened by remember { mutableStateOf<String?>(null) }

    // Строка появляется только с ответом: список не даёт пустых обещаний.
    //
    // День, в котором речи не оказалось (нажал и промолчал, микрофон не
    // услышал), строки не получает вовсе — пустая строка с датой читается как
    // сбой, а не как «нечего сказать». Само «ничего», сказанное вслух, — это
    // ответ, и оно проходит: там непустой транскрипт.
    val shown = days.filter { !it.line.isNullOrBlank() || !it.transcript.isNullOrBlank() }

    val context = androidx.compose.ui.platform.LocalContext.current
    // Сегодняшний день ещё не рассказан — предлагаем рассказать (Р-23.1).
    // Рассказан — предлагать нечего: день не правится, это память, а не дело.
    val today = LocalDate.now().toString()
    val canTell = shown.none { it.date == today }

    if (shown.isEmpty()) {
        EmptyDays(canTell = canTell, onTell = { vm.tellAboutDay(context) })
        return
    }

    LazyColumn(contentPadding = PaddingValues(top = Space.xs, bottom = Space.xxl)) {
        if (canTell) {
            item(key = "tell-today") {
                MetaText(
                    text = stringResource(R.string.days_tell),
                    color = Prinyal.colors.accentSelf,
                    modifier = Modifier
                        .padding(horizontal = Space.screen)
                        .padding(bottom = Space.m)
                        .tap { vm.tellAboutDay(context) },
                )
            }
        }
        items(shown, key = { it.date }) { day ->
            DayRow(
                day = day,
                opened = opened == day.date,
                onToggle = { opened = if (opened == day.date) null else day.date },
                onEdit = { vm.editDayLine(day.date, it) },
                onRetell = { vm.retellDay(context, day.date) },
            )
        }
    }
}

@Composable
private fun DayRow(
    day: DayEntity,
    opened: Boolean,
    onToggle: () -> Unit,
    onEdit: (String) -> Unit,
    onRetell: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .tap(onClick = onToggle)
            .padding(horizontal = Space.screen)
            .padding(bottom = Space.ml),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            // Дата колонкой 52: столбец держит даты в линию, и список
            // читается сверху вниз, как оглавление.
            MetaText(
                text = Dates.day(LocalDate.parse(day.date)),
                color = Prinyal.colors.inkFaint,
                // 64, а не 52: моноширинные «21 авг» в 52 не влезли и дата
                // сложилась в два этажа — столбец перестал быть столбцом.
                maxLines = 1,
                modifier = Modifier.width(64.dp),
            )
            Text(
                // Впечатление не обрезается ни на какой длине (12d): пик дня
                // не режут многоточием. Ещё не сжалось — печатаем расшифровку.
                text = day.line ?: day.transcript.orEmpty(),
                style = Prinyal.type.body,
                color = Prinyal.colors.ink,
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = opened,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    ai.prinim.prinyal.ui.theme.Motion.Polish.EXPAND_MS,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            ) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = androidx.compose.animation.core.tween(
                    ai.prinim.prinyal.ui.theme.Motion.Polish.EXPAND_MS,
                ),
            ) + androidx.compose.animation.fadeOut(),
        ) {
        if (opened) {
            var draft by remember(day.date) { mutableStateOf<String?>(null) }

            // Раскрытие на месте, не карточка: 176 знаков не стоят перехода,
            // а в «Днях» смысл — в соседстве дней (12c).
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.s)
                    .background(Prinyal.colors.wellSurface, Radius.control)
                    .padding(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                // Впечатление правится и руками, и голосом (Р-24.3): модель
                // сжимает ответ, и одной попытки на вечер мало, если сжала мимо.
                draft?.let { text ->
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = { draft = it },
                        textStyle = Prinyal.type.body.copy(color = Prinyal.colors.ink),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                            Prinyal.colors.accentSelf,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                    )
                    // Поле само забирает фокус: без этого оно открывалось
                    // немым — клавиатуры нет, и «Поправить» выглядит сломанным.
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                        MetaText(
                            text = stringResource(R.string.day_save),
                            color = Prinyal.colors.accentSelf,
                            modifier = Modifier.tap {
                                onEdit(text)
                                draft = null
                            },
                        )
                        MetaText(
                            text = stringResource(R.string.day_cancel),
                            color = Prinyal.colors.inkMuted,
                            modifier = Modifier.tap { draft = null },
                        )
                    }
                }
                if (draft == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.ml)) {
                        MetaText(
                            text = stringResource(R.string.day_fix),
                            color = Prinyal.colors.accentSelf,
                            modifier = Modifier.tap {
                                draft = day.line ?: day.transcript.orEmpty()
                            },
                        )
                        MetaText(
                            text = stringResource(R.string.day_retell),
                            color = Prinyal.colors.inkMuted,
                            modifier = Modifier.tap(onClick = onRetell),
                        )
                    }
                }

                MetaText(stringResource(R.string.day_said), color = Prinyal.colors.inkFaint)
                Text(
                    text = day.transcript.orEmpty().ifBlank { "…" },
                    style = Prinyal.type.body,
                    color = Prinyal.colors.inkMuted,
                )
                // Единственная запись, которой нет в ленте, — «послушать»
                // есть только здесь (ответ дизайнера на вопрос 5).
                DayAudio(File(day.audioPath), day.durationMs)
            }
        }
        }
    }
}

@Composable
private fun DayAudio(file: File, durationMs: Long) {
    if (!file.exists()) return
    var playing by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }
    val seconds = (durationMs / 1000).toInt()
    MetaText(
        text = stringResource(R.string.note_audio_play) + " · %d:%02d".format(seconds / 60, seconds % 60),
        color = Prinyal.colors.inkMuted,
        modifier = Modifier.tap {
            if (playing) {
                runCatching { player.stop() }
                playing = false
            } else {
                runCatching {
                    player.reset()
                    player.setDataSource(file.absolutePath)
                    player.setOnCompletionListener { playing = false }
                    player.prepare()
                    player.start()
                    playing = true
                }
            }
        },
    )
}

@Composable
private fun EmptyDays(canTell: Boolean, onTell: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Space.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.days_empty_title),
            style = Prinyal.type.voice,
            color = Prinyal.colors.inkMuted,
        )
        Text(
            text = stringResource(R.string.days_empty_body),
            style = Prinyal.type.voice,
            color = Prinyal.colors.inkFaint,
            modifier = Modifier.padding(top = Space.s),
        )
        // Ждать до вечера, когда рассказать хочется сейчас, незачем.
        if (canTell) {
            MetaText(
                text = stringResource(R.string.days_tell),
                color = Prinyal.colors.accentSelf,
                modifier = Modifier.padding(top = Space.ml).tap(onClick = onTell),
            )
        }
    }
}
