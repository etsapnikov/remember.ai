package ai.prinim.prinyal.ui

import ai.prinim.prinyal.R
import ai.prinim.prinyal.ui.theme.MetaText
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Промежуток между «Запомнил.» и пунктами (Р-15.2, Д-9).
 *
 * Это главная пауза продукта: разбор с рассуждениями идёт около минуты. Пустая
 * карточка всё это время читается как «сломалось» — человек уже видел
 * квитанцию и ждёт результата.
 *
 * Индикатор — родственник кольца записи, а не спиннер: тот же скруглённый
 * квадрат, то же дыхание. Спиннер сказал бы «идёт загрузка», а кольцо говорит
 * «я занят тем же, чем был занят на записи».
 *
 * Скелетоны стоят на местах будущих пунктов, чтобы доехавшие пункты не сдвигали
 * раскладку: строка появляется там, где уже была её тень.
 */
@Composable
fun ParsingBlock(modifier: Modifier = Modifier) {
    val breath = rememberInfiniteTransition(label = "parsing")
    val phase by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.Pulse.durationMs, easing = Motion.Pulse.easing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.ml)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BreathingMark(phase)
            MetaText(stringResource(R.string.transcript_parsing), color = Prinyal.colors.inkFaint)
        }

        // Три тени: столько пунктов в записи чаще всего. Больше — раскладка
        // осядет при доезде, меньше — подпрыгнет; три ошибаются в обе стороны
        // одинаково редко.
        repeat(SKELETON_ROWS) { index ->
            SkeletonRow(phase = phase, wide = index != SKELETON_ROWS - 1)
        }
    }
}

/** Ошибка разбора: строка вместо пунктов, тап повторяет (Д-9). */
@Composable
fun ParsingFailed(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    MetaText(
        text = stringResource(R.string.note_parse_failed),
        color = Prinyal.colors.accentSelf,
        modifier = modifier.clickable(onClick = onRetry),
    )
}

@Composable
private fun BreathingMark(phase: Float) {
    val color = Prinyal.colors.accentSelf
    Canvas(Modifier.size(MARK_SIZE)) {
        val side = size.minDimension * (0.62f + 0.24f * phase)
        drawRoundRect(
            color = color.copy(alpha = 0.35f + 0.45f * phase),
            topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
            size = Size(side, side),
            cornerRadius = CornerRadius(side * 0.3f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

@Composable
private fun SkeletonRow(phase: Float, wide: Boolean) {
    val tone = Prinyal.colors.wellSurface.copy(alpha = 0.55f + 0.35f * phase)
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Box(width = if (wide) 1f else 0.62f, height = TITLE_HEIGHT, color = tone)
        Box(width = 0.38f, height = META_HEIGHT, color = tone)
    }
}

@Composable
private fun Box(width: Float, height: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .background(color, Radius.control),
    )
}

private val MARK_SIZE = 14.dp
/** Высоты повторяют строки пункта: тень должна быть того же роста, что текст. */
private val TITLE_HEIGHT = 21.dp
private val META_HEIGHT = 13.dp
private const val SKELETON_ROWS = 3
