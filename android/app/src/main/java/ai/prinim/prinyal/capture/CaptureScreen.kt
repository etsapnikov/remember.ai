package ai.prinim.prinyal.capture

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.components.RecordKey
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.PrinyalTheme
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview

/**
 * Состояние экрана захвата. Простые observable-поля вместо ViewModel: activity живёт
 * секунды и умирает вместе с жестом — переживать поворот ей нечего.
 */
class CaptureState {
    var recording by mutableStateOf(false)
    var receipt by mutableStateOf(false)
    var needsPermission by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var tooShort by mutableStateOf(false)
    var elapsedMs by mutableLongStateOf(0L)
    var level by mutableFloatStateOf(0f)
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
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onGrant: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Prinyal.colors.paper)
            .pointerInput(state.recording) {
                if (!state.recording) return@pointerInput
                detectVerticalDragGestures { _, dragAmount ->
                    // Свайп вниз — отмена. Порог крупный: случайное движение
                    // пальцем не должно стирать сказанное.
                    if (dragAmount > 24f) onCancel()
                }
            }
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
            else -> Recording(state)
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
private fun Recording(state: CaptureState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(Space.screen),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            MetaText(
                text = formatElapsed(state.elapsedMs),
                color = Prinyal.colors.record,
            )
        }

        // Ключевые действия — в нижней трети: телефон держат одной рукой.
        RecordKey(pulsing = state.recording, level = state.level)

        Box(Modifier.weight(0.5f), contentAlignment = Alignment.TopCenter) {
            Text(
                text = stringResource(
                    if (state.recording) R.string.capture_cancel_hint else R.string.capture_hint
                ),
                style = Prinyal.type.body,
                color = Prinyal.colors.inkFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.l),
            )
        }
    }
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
            onStop = {}, onCancel = {}, onGrant = {},
        )
    }
}
