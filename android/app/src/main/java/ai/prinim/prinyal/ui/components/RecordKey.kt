package ai.prinim.prinyal.ui.components

import ai.prinim.prinyal.ui.theme.KeyColors
import ai.prinim.prinyal.ui.theme.KeyMetrics
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.PrinyalTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Клавиша записи — главный носитель бренда: она же иконка, она же виджет 1×1
 * (ТЗ айдентики §5, п. 1). Три состояния по спеке R1.2 §12:
 *
 *  - **idle** — «заряд есть, но не идёт»: корпус и колпачок гаснут до цвета
 *    поверхности, красной остаётся только точка. Клавиша = кнопка «начать».
 *  - **запись** — колпачок залит красным, точка стала квадратом: это и есть знак
 *    «стоп», без галочек и микрофонов. Клавиша = кнопка «стоп».
 *  - **нажатие** — колпачок 84 → 78 и вниз на 4 dp, тень схлопывается: клавиша
 *    физически продавлена.
 *
 * Микрофон стартует на **отпускании**, поэтому клавиша сама разводит press/release,
 * а не отдаёт всё в `clickable`.
 *
 * Кольцо-амплитуда (§13, вариант Б) рисуется здесь же: одна фигура обслуживает и
 * «я тебя слышу», и обратный отсчёт тишины — меняются только цвет и радиус.
 */
@Composable
fun RecordKey(
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    /** Сглаженная амплитуда 0..1 — считается в [rememberAmplitude]. */
    level: Float = 0f,
    /** Идёт отсчёт до авто-стопа: кольцо остывает и стягивается. */
    silence: Boolean = false,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }

    val capScale by animateFloatAsState(
        targetValue = if (pressed) KeyMetrics.capPressed / KeyMetrics.cap else 1f,
        animationSpec = if (pressed) {
            tween(Motion.KeyPress.durationMs, easing = Motion.KeyPress.easing)
        } else {
            tween(Motion.KeyRelease.durationMs, easing = Motion.KeyRelease.easing)
        },
        label = "cap",
    )
    val capDrop by animateFloatAsState(
        targetValue = if (pressed) KeyMetrics.capDrop.value else 0f,
        animationSpec = if (pressed) {
            tween(Motion.KeyPress.durationMs, easing = Motion.KeyPress.easing)
        } else {
            tween(Motion.KeyRelease.durationMs, easing = Motion.KeyRelease.easing)
        },
        label = "drop",
    )

    // Смена состояния: цвета едут одним пресетом (§12).
    val shift = tween<androidx.compose.ui.graphics.Color>(
        Motion.StateShift.durationMs, easing = Motion.StateShift.easing,
    )
    val housing by animateColorAsState(
        if (recording) KeyColors.recHousing else KeyColors.idleHousing, shift, label = "housing",
    )
    val housingEdge by animateColorAsState(
        if (recording) KeyColors.recHousingEdge else KeyColors.idleHousingEdge, shift, label = "edge",
    )
    val cap by animateColorAsState(
        if (recording) KeyColors.recCap else KeyColors.idleCap, shift, label = "capColor",
    )
    // Центр: круг-точка в idle → квадрат-«стоп» в записи. Форму ведём числом,
    // чтобы переход был не подменой, а морфингом скругления.
    val stopness by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = tween(Motion.StateShift.durationMs, easing = Motion.StateShift.easing),
        label = "stopness",
    )
    val ringPresence by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = tween(
            Motion.StateShift.durationMs,
            delayMillis = if (recording) Motion.StateShiftRingDelayMs else 0,
            easing = Motion.StateShift.easing,
        ),
        label = "ring",
    )
    val ringColor by animateColorAsState(
        if (silence) KeyColors.ringIdle else KeyColors.ring, shift, label = "ringColor",
    )

    // Во время отсчёта тишины кольцо стягивается к корпусу и не дышит.
    val amplitude = if (silence) 0f else level
    // Показатель 1.25, а не 2 из спеки: квадрат был нужен, чтобы шорох не мерцал,
    // но эту работу уже делает логарифмическая нормировка — тихая комната падает
    // в ноль до кольца. Квадрат поверх неё просто съедал ход.
    val shaped = amplitude.pow(RING_EXPONENT)
    val ringSide = if (silence) SILENCE_SIDE else RING_SIDE + RING_SIDE_GAIN * shaped
    val ringCorner = RING_CORNER + RING_CORNER_GAIN * shaped
    val ringWidth = RING_WIDTH + RING_WIDTH_GAIN * amplitude
    val ringAlpha = if (silence) 0.5f else RING_ALPHA + RING_ALPHA_GAIN * amplitude

    Box(
        modifier
            .size(RING_SIDE.dp + RING_SIDE_GAIN.dp + 12.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        pressed = true
                        onPress()
                        tryAwaitRelease()
                        pressed = false
                        // Микрофон стартует на отпускании, не по завершении
                        // анимации (§12): иначе первые 160 мс речи теряются.
                        onRelease()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(RING_SIDE.dp + RING_SIDE_GAIN.dp + 12.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val housingPx = KeyMetrics.housing.toPx()
            val capPx = KeyMetrics.cap.toPx() * capScale
            val dropPx = capDrop.dp.toPx()

            // Кольцо-амплитуда: одна фигура, не волна и не эквалайзер (§13).
            if (ringPresence > 0.01f) {
                val sidePx = ringSide.dp.toPx() * (0.92f + 0.08f * ringPresence)
                val strokePx = ringWidth.dp.toPx()
                drawRoundRect(
                    color = ringColor.copy(alpha = ringAlpha * ringPresence),
                    topLeft = Offset(center.x - sidePx / 2f, center.y - sidePx / 2f),
                    size = Size(sidePx, sidePx),
                    cornerRadius = CornerRadius(ringCorner.dp.toPx()),
                    style = Stroke(width = strokePx),
                )
            }

            // Корпус: кант сверху, тело ниже.
            val housingTopLeft = Offset(center.x - housingPx / 2f, center.y - housingPx / 2f)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(housingEdge, housing),
                    startY = housingTopLeft.y,
                    endY = housingTopLeft.y + housingPx,
                ),
                topLeft = housingTopLeft,
                size = Size(housingPx, housingPx),
                cornerRadius = CornerRadius(housingPx * 0.24f),
            )

            // Тень под колпачком схлопывается при нажатии.
            val shadowPx = ((if (recording) 4f else 3f) * (1f - capScale.let { (1f - it) * 8f }))
                .coerceAtLeast(0f).dp.toPx()
            if (shadowPx > 0.5f) {
                drawCircle(
                    color = KeyColors.idleHousing.copy(alpha = 0.55f),
                    radius = capPx / 2f,
                    center = center + Offset(0f, shadowPx + dropPx),
                )
            }

            // Колпачок.
            drawCircle(
                color = cap,
                radius = capPx / 2f,
                center = center + Offset(0f, dropPx),
            )

            // Центр: точка (idle) ↔ квадрат «стоп» (запись).
            val markPx = KeyMetrics.dot.toPx()
            val markColor = if (stopness > 0.5f) KeyColors.recStopMark else KeyColors.idleDot
            drawRoundRect(
                color = markColor,
                topLeft = Offset(
                    center.x - markPx / 2f,
                    center.y - markPx / 2f + dropPx,
                ),
                size = Size(markPx, markPx),
                // Скругление едет от круга (r = половина стороны) к квадрату r5.
                cornerRadius = CornerRadius(
                    (markPx / 2f) * (1f - stopness) + STOP_RADIUS.dp.toPx() * stopness,
                ),
            )
        }
    }
}

// Кольцо-амплитуда (токены key.ring)
private const val RING_SIDE = 120f
private const val RING_SIDE_GAIN = 44f
private const val RING_CORNER = 36f
private const val RING_CORNER_GAIN = 10f
private const val RING_WIDTH = 2f
private const val RING_WIDTH_GAIN = 2.5f
private const val RING_EXPONENT = 1.25f
private const val RING_ALPHA = 0.35f
private const val RING_ALPHA_GAIN = 0.65f
private const val SILENCE_SIDE = 126f
private const val STOP_RADIUS = 5f

@Preview(showBackground = true, backgroundColor = 0xFF1A1512, widthDp = 220, heightDp = 220)
@Composable
private fun KeyIdle() {
    PrinyalTheme(dark = true) { Box(Modifier.size(220.dp), Alignment.Center) { RecordKey() } }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1512, widthDp = 220, heightDp = 220)
@Composable
private fun KeyRecording() {
    PrinyalTheme(dark = true) {
        Box(Modifier.size(220.dp), Alignment.Center) { RecordKey(recording = true, level = 0.7f) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1512, widthDp = 220, heightDp = 220)
@Composable
private fun KeySilence() {
    PrinyalTheme(dark = true) {
        Box(Modifier.size(220.dp), Alignment.Center) {
            RecordKey(recording = true, level = 0f, silence = true)
        }
    }
}
