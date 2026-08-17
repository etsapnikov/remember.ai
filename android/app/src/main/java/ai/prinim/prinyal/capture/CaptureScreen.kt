package ai.prinim.prinyal.capture

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.components.RecordKey
import ai.prinim.prinyal.ui.components.rememberAmplitude
import ai.prinim.prinyal.ui.theme.KeyColors
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.PrinyalTheme
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview

/**
 * Состояние экрана захвата. Простые observable-поля вместо ViewModel: activity живёт
 * секунды и умирает вместе с жестом — переживать поворот ей нечего.
 */
class CaptureState {
    var recording by mutableStateOf(false)
    /** Вернулись из ленты: запись завершена, новая — по нажатию клавиши (Р-К). */
    var idle by mutableStateOf(false)
    var showCancelHint by mutableStateOf(true)
    var showUpHint by mutableStateOf(true)
    var receipt by mutableStateOf(false)
    var needsPermission by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var tooShort by mutableStateOf(false)
    /**
     * Первые слова заметки, к которой дописываем; null — обычная запись.
     * Плашка сообщает, а не спрашивает: человек уже решил дописать (Д-3).
     */
    var appendHint by mutableStateOf<String?>(null)
    var elapsedMs by mutableLongStateOf(0L)
    var level by mutableFloatStateOf(0f)
    /**
     * Осталось до авто-стопа, мс; 0 — тишина ещё не считается. Авто-стоп перестаёт
     * быть внезапным: полторы секунды видно, что он идёт (R1.2 §13).
     */
    var silenceLeftMs by mutableLongStateOf(0L)
}

/**
 * Почти пустой экран: клавиша, пульс, таймер. Ничего, что требовало бы решения
 * (ТЗ UI §3.2).
 *
 * Live-транскрипта здесь нет — осознанное отступление R1 (PRD §1): он требует
 * стриминговой ASR, вместо него пульс и таймер.
 */
@Composable
fun CaptureScreen(
    state: CaptureState,
    hasNotes: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onGrant: () -> Unit,
    onStart: () -> Unit = {},
) {
    // Вертикальные жесты живут в CaptureHost: вверх тянет лист ленты, вниз отменяет.
    Box(
        Modifier
            .fillMaxSize()
            .background(Prinyal.colors.paper)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // Ripple здесь неуместен: тап по экрану — это стоп, а не «кнопка».
                indication = null,
                enabled = state.recording,
                onClick = onStop,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.receipt -> Receipt()
            state.needsPermission -> PermissionRequest(onGrant)
            state.failed -> Message(stringResource(R.string.error_asr_failed))
            state.tooShort -> Message(stringResource(R.string.capture_too_short))
            else -> Recording(state, hasNotes, onStop = onStop, onStart = onStart)
        }

        // Плашка контекста дописывания (Д-3): прижата к верху, набрана
        // служебным моно. Она сообщает, а не спрашивает — человек уже решил
        // дописать, и переспрашивать его на экране записи не за чем.
        state.appendHint?.let { hint ->
            MetaText(
                text = if (hint.isBlank()) {
                    stringResource(R.string.capture_append_plain)
                } else {
                    stringResource(R.string.capture_append, hint)
                },
                color = Prinyal.colors.inkFaint,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = Space.screen, vertical = Space.m),
            )
        }
    }
}

/**
 * Квитанция. Слово приходит быстро и уверенно, затем уходит вверх выдохом
 * (моушн `receipt` + `noteAway` из токенов). Анимация одноразовая: экран живёт 0.6 с.
 */
@Composable
private fun Receipt() {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(Motion.Receipt.durationMs, easing = Motion.Receipt.easing),
        )
    }

    Text(
        text = stringResource(R.string.receipt),
        style = Prinyal.type.display,
        color = Prinyal.colors.accentSelf,
        modifier = Modifier.graphicsLayer {
            alpha = appear.value
            translationY = Motion.NoteAwayTranslateY * (1f - appear.value) * -1f
        },
    )
}

@Composable
private fun Recording(
    state: CaptureState,
    hasNotes: Boolean,
    onStop: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.screen),
    ) {
        // Воздух сверху: клавиша живёт в нижней трети, телефон держат одной рукой.
        Box(Modifier.weight(1f))

        // Таймер над клавишей. Место занято даже в idle — «0:00» в disabled-цвете
        // честнее пустоты: экран не начинает врать (R1.2 §12).
        Text(
            text = formatElapsed(state.elapsedMs),
            style = Prinyal.type.timer,
            color = if (state.recording) KeyColors.recTimer else KeyColors.idleTimer,
        )
        Box(Modifier.height(Space.ml))

        // Клавиша — и есть кнопка: стоп во время записи, старт в idle.
        // Микрофон стартует на отпускании (§12), поэтому press/release разведены.
        val level by rememberAmplitude(if (state.recording) state.level else 0f)
        RecordKey(
            recording = state.recording,
            level = level,
            silence = state.silenceLeftMs > 0,
            onRelease = { if (state.recording) onStop() else onStart() },
        )

        Box(Modifier.height(Space.l))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Отсчёт до авто-стопа вытесняет обычную подпись: сейчас важнее он.
            if (state.silenceLeftMs > 0) {
                HintLine(
                    stringResource(
                        R.string.capture_silence,
                        "%.1f".format(state.silenceLeftMs / 1000f).replace('.', ','),
                    )
                )
            } else {
                HintLine(
                    stringResource(
                        if (state.recording) R.string.capture_stop_hint
                        else R.string.capture_idle_hint
                    )
                )
            }

            // Подсказки жестов (§8): гаснут по счётчикам применений.
            if (state.recording && state.showCancelHint) {
                HintLine(stringResource(R.string.capture_cancel_hint))
            }
            if (state.showUpHint && hasNotes) {
                HintLine(stringResource(R.string.capture_hint_up))
            }
        }

        Box(Modifier.weight(0.55f))
    }
}

@Composable
private fun HintLine(text: String) {
    Text(
        text = text,
        style = Prinyal.type.body,
        color = Prinyal.colors.inkFaint,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PermissionRequest(onGrant: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(Space.screen),
    ) {
        Text(
            text = stringResource(R.string.capture_no_mic),
            style = Prinyal.type.body,
            color = Prinyal.colors.ink,
            textAlign = TextAlign.Center,
        )
        Box(Modifier.height(Space.l))
        Text(
            text = stringResource(R.string.capture_mic_grant),
            style = Prinyal.type.label,
            color = Prinyal.colors.accentSelf,
            modifier = Modifier
                .clickable(onClick = onGrant)
                .padding(Space.sm),
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = Prinyal.type.body,
        color = Prinyal.colors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(Space.screen),
    )
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Preview(showBackground = true, backgroundColor = 0xFFFBF6F0, heightDp = 640)
@Composable
private fun CapturePreview() {
    PrinyalTheme {
        CaptureScreen(
            state = CaptureState().apply { recording = true; elapsedMs = 7_400; level = 0.6f },
            hasNotes = true,
            onStop = {}, onCancel = {}, onGrant = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBF6F0, heightDp = 640)
@Composable
private fun ReceiptPreview() {
    PrinyalTheme {
        CaptureScreen(
            state = CaptureState().apply { receipt = true },
            hasNotes = true,
            onStop = {}, onCancel = {}, onGrant = {},
        )
    }
}
