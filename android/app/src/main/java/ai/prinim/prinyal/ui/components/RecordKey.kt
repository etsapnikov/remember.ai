package ai.prinim.prinyal.ui.components

import ai.prinim.prinyal.ui.theme.KeyMetrics
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.PrinyalTheme
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Клавиша записи — главный носитель бренда: она же иконка, она же виджет 1×1
 * (ТЗ айдентики §5, п. 1).
 *
 * Геометрия из токенов (`key`): корпус 112, колпачок 84, нажатый 78, точка 26.
 * Аффорданс намеренно «кнопочный» — на неё должно хотеться нажать; микрофона и
 * волны в знаке нет, они запрещены (ТЗ айдентики §4).
 */
@Composable
fun RecordKey(
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    pressed: Boolean = false,
    /** Громкость 0..1 — пульс дышит голосом, а не таймером. */
    level: Float = 0f,
) {
    val colors = Prinyal.colors

    val capScale by animateFloatAsState(
        targetValue = if (pressed) KeyMetrics.capPressed / KeyMetrics.cap else 1f,
        animationSpec = tween(120),
        label = "cap",
    )

    val transition = rememberInfiniteTransition(label = "pulse")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(Motion.Pulse.durationMs, easing = Motion.Pulse.easing),
            RepeatMode.Restart,
        ),
        label = "wave",
    )

    Box(
        modifier.size(KeyMetrics.housing * Motion.PulseMaxScale),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(KeyMetrics.housing * Motion.PulseMaxScale)) {
            val housingPx = KeyMetrics.housing.toPx()
            val capPx = KeyMetrics.cap.toPx() * capScale
            val center = Offset(size.width / 2f, size.height / 2f)

            // Пульс: живое «слушаю». Радиус подрастает с громкостью, чтобы кольцо
            // отвечало голосу, а не просто тикало.
            if (pulsing) {
                val reach = 1f + (Motion.PulseMaxScale - 1f) * (0.55f + 0.45f * level)
                val radius = housingPx / 2f * (1f + (reach - 1f) * wave)
                val alpha = (Motion.PulseOpacity.first * (1f - wave)).coerceAtLeast(0f)
                drawCircle(colors.record.copy(alpha = alpha), radius = radius, center = center)
            }

            // Корпус: тёплый пластик, слегка объёмный.
            val housingTopLeft = Offset(center.x - housingPx / 2f, center.y - housingPx / 2f)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(
                        if (pressed) colors.recordPressed else colors.record,
                        colors.recordPressed,
                    ),
                    startY = housingTopLeft.y,
                    endY = housingTopLeft.y + housingPx,
                ),
                topLeft = housingTopLeft,
                size = Size(housingPx, housingPx),
                cornerRadius = CornerRadius(housingPx * 0.24f),
            )

            // Колпачок: утопленный круг. Это «куда жать», без единой подписи.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors.recordWell, colors.recordPressed),
                    center = center + Offset(0f, capPx * 0.12f),
                    radius = capPx / 2f,
                ),
                radius = capPx / 2f,
                center = center,
            )

            // Точка — та самая точка из «Принял.»: знак спокойной завершённости.
            drawCircle(
                color = colors.record.copy(alpha = if (pulsing) 0.55f else 0.3f),
                radius = KeyMetrics.dot.toPx() / 2f,
                center = center,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFFBF6F0, widthDp = 200, heightDp = 200)
@Composable
private fun RecordKeyRest() {
    PrinyalTheme { Box(Modifier.size(200.dp), Alignment.Center) { RecordKey() } }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1512, widthDp = 200, heightDp = 200)
@Composable
private fun RecordKeyPulsing() {
    PrinyalTheme(dark = true) {
        Box(Modifier.size(200.dp), Alignment.Center) { RecordKey(pulsing = true, level = 0.7f) }
    }
}
