package ai.prinim.prinyal.ui.components

import ai.prinim.prinyal.ui.theme.KeyMetrics
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.PrinyalTheme
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue
import kotlin.math.sin

/**
 * Клавиша записи (полишинг 1.3, ТЗ §5, экран 01/30).
 *
 * До 1.3 клавиша была «пластиковым предметом»: корпус с кантом, колпачок,
 * тень под ним, точка в центре и кольцо-амплитуда вокруг. Модель была
 * последовательной, но держалась на собственной палитре из двенадцати цветов —
 * а §2 говорит, что акцент в продукте один и служит действию. Клавиша осталась
 * единственным местом, где это правило не действовало.
 *
 * Теперь это одно акцентное поле 132 px с ореолом 10 px и меткой 40 px цвета
 * бумаги. Знак «стоп» никуда не делся — он и есть метка; в покое та же метка
 * стягивается в точку и красит себя акцентом на поверхности.
 *
 * Амплитуда переехала в [LevelBars] над клавишей: кольцо вокруг кнопки
 * дублировало её же границу и читалось как обводка, а не как «я тебя слышу».
 *
 * Микрофон стартует на **отпускании**, поэтому клавиша сама разводит
 * press/release, а не отдаёт всё в `clickable`.
 */
@Composable
fun RecordKey(
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    /** Сглаженная амплитуда 0..1 — считается в [rememberAmplitude]. */
    level: Float = 0f,
    /** Идёт отсчёт до авто-стопа: ореол гаснет. */
    silence: Boolean = false,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
) {
    var pressed by remember { mutableStateOf(false) }

    val press = if (pressed) {
        tween<Float>(Motion.KeyPress.durationMs, easing = Motion.KeyPress.easing)
    } else {
        tween<Float>(Motion.KeyRelease.durationMs, easing = Motion.KeyRelease.easing)
    }
    val scale by animateFloatAsState(if (pressed) PRESS_SCALE else 1f, press, label = "scale")
    val drop by animateFloatAsState(
        if (pressed) KeyMetrics.capDrop.value else 0f, press, label = "drop",
    )

    val shift = tween<androidx.compose.ui.graphics.Color>(
        Motion.StateShift.durationMs, easing = Motion.StateShift.easing,
    )
    val housing by animateColorAsState(
        when {
            !recording -> Prinyal.key.idleHousing
            pressed -> Prinyal.colors.accentPressed
            else -> Prinyal.key.housing
        },
        shift, label = "housing",
    )
    val mark by animateColorAsState(
        if (recording) Prinyal.key.mark else Prinyal.key.idleMark, shift, label = "mark",
    )
    // Метка: точка в покое → квадрат «стоп» в записи. Ведём числом, чтобы переход
    // был морфингом скругления, а не подменой фигуры.
    val stopness by animateFloatAsState(
        targetValue = if (recording) 1f else 0f,
        animationSpec = tween(Motion.StateShift.durationMs, easing = Motion.StateShift.easing),
        label = "stopness",
    )
    val haloAlpha by animateFloatAsState(
        targetValue = when {
            !recording -> 0f
            silence -> 0.35f
            else -> 1f
        },
        animationSpec = tween(Motion.StateShift.durationMs, easing = Motion.StateShift.easing),
        label = "halo",
    )

    val halo = Prinyal.key.housingHalo
    val side = KeyMetrics.housing + KeyMetrics.halo * 2

    Box(
        modifier
            .size(side)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
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
        Canvas(Modifier.size(side)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val dropPx = drop.dp.toPx()
            val bodyPx = KeyMetrics.housing.toPx() * scale
            val bodyRadius = KeyMetrics.housingRadius.toPx() * scale

            // Ореол 10 px — не тень, а поле вокруг клавиши: он показывает, что
            // клавиша «горит», и гаснет на отсчёте тишины.
            if (haloAlpha > 0.01f) {
                val haloPx = bodyPx + KeyMetrics.halo.toPx() * 2
                drawRoundRect(
                    color = halo.copy(alpha = halo.alpha * haloAlpha),
                    topLeft = Offset(center.x - haloPx / 2f, center.y - haloPx / 2f + dropPx),
                    size = Size(haloPx, haloPx),
                    cornerRadius = CornerRadius(bodyRadius + KeyMetrics.halo.toPx()),
                )
            }

            drawRoundRect(
                color = housing,
                topLeft = Offset(center.x - bodyPx / 2f, center.y - bodyPx / 2f + dropPx),
                size = Size(bodyPx, bodyPx),
                cornerRadius = CornerRadius(bodyRadius),
            )

            // Метка: точка (покой) ↔ квадрат «стоп» (запись).
            val full = KeyMetrics.mark.toPx() * scale
            val dot = IDLE_MARK.dp.toPx() * scale
            val markPx = dot + (full - dot) * stopness
            drawRoundRect(
                color = mark,
                topLeft = Offset(center.x - markPx / 2f, center.y - markPx / 2f + dropPx),
                size = Size(markPx, markPx),
                cornerRadius = CornerRadius(
                    (markPx / 2f) * (1f - stopness) +
                        KeyMetrics.markRadius.toPx() * stopness,
                ),
            )
        }
    }
}

/**
 * Индикатор уровня над клавишей (ТЗ §5, экран 01). Восемь столбиков 4 px в поле
 * высотой 44: фигура показывает, что продукт слышит, и не претендует на то,
 * чтобы быть осциллографом — форма волны здесь ничего не сообщает.
 *
 * Место занято всегда: в покое столбики стоят на минимуме. Пустота на этом
 * месте читалась бы как «микрофон отвалился».
 */
@Composable
fun LevelBars(
    level: Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val shown by animateFloatAsState(
        targetValue = if (active) level.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(Motion.StateShift.durationMs, easing = Motion.StateShift.easing),
        label = "level",
    )
    val accent = Prinyal.colors.accentSelf
    Row(
        modifier.height(KeyMetrics.levelHeight),
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(BARS) { i ->
            // Профиль столбиков фиксированный: середина выше краёв. Случайность
            // здесь выглядела бы живее, но каждая перерисовка дёргала бы фигуру.
            val shape = PROFILE[i]
            val h = BAR_MIN + (KeyMetrics.levelHeight.value - BAR_MIN) * shape * shown
            Box(
                Modifier
                    .width(KeyMetrics.levelBar)
                    .height(h.dp)
                    .background(
                        accent.copy(alpha = 0.35f + 0.65f * shape * shown),
                        RoundedCornerShape(KeyMetrics.levelBar / 2),
                    )
            )
        }
    }
}

private const val PRESS_SCALE = 0.97f
private const val IDLE_MARK = 22f
private const val BARS = 8
private const val BAR_GAP = 5f
private const val BAR_MIN = 12f

/** Симметричный профиль: края тише центра. */
private val PROFILE = floatArrayOf(0.30f, 0.58f, 0.92f, 0.50f, 0.74f, 0.26f, 0.64f, 0.40f)

@Preview(showBackground = true, backgroundColor = 0xFFFAF4EC, widthDp = 220, heightDp = 220)
@Composable
private fun KeyIdle() {
    PrinyalTheme(dark = false) { Box(Modifier.size(220.dp), Alignment.Center) { RecordKey() } }
}

@Preview(showBackground = true, backgroundColor = 0xFF191411, widthDp = 220, heightDp = 220)
@Composable
private fun KeyRecording() {
    PrinyalTheme(dark = true) {
        Box(Modifier.size(220.dp), Alignment.Center) { RecordKey(recording = true, level = 0.7f) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF191411, widthDp = 220, heightDp = 220)
@Composable
private fun KeySilence() {
    PrinyalTheme(dark = true) {
        Box(Modifier.size(220.dp), Alignment.Center) {
            RecordKey(recording = true, level = 0f, silence = true)
        }
    }
}
