package ai.prinim.prinyal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.PrinyalTheme

/**
 * Глифы типов айтемов (`assets/type-glyphs.png`).
 *
 * Рисуются кодом, а не растром: они живут и в списке 20dp, и в нотификации, и в
 * карточке — растр на дешёвом экране мылится. Формы намеренно геометричные и без
 * «иконочной» милоты: продукт не украшает, он маркирует.
 */
@Composable
fun TypeGlyph(
    type: ItemType,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = Prinyal.colors.ink,
) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.11f
        when (type) {
            ItemType.BUY -> drawBuy(color, stroke)
            ItemType.DO -> drawDo(color)
            ItemType.TELL -> drawTell(color, stroke)
            ItemType.DATE -> drawDate(color, stroke)
            ItemType.THOUGHT -> drawThought(color, stroke)
            ItemType.FACT -> drawFact(color, stroke)
            ItemType.DECISION -> drawDecision(color, stroke)
        }
    }
}

/** купить — открытая коробка: в неё ещё предстоит положить. */
private fun DrawScope.drawBuy(color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val left = w * 0.16f
    val right = w * 0.84f
    val top = h * 0.24f
    val bottom = h * 0.78f
    val path = Path().apply {
        moveTo(left, top)
        lineTo(left, bottom)
        lineTo(right, bottom)
        lineTo(right, top)
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Square))
}

/** сделать — плотная точка: дело есть, оно одно. */
private fun DrawScope.drawDo(color: Color) {
    drawCircle(color, radius = size.minDimension * 0.28f, center = center)
}

/** сказать — два узла и связь между ними. */
private fun DrawScope.drawTell(color: Color, stroke: Float) {
    val r = size.minDimension * 0.15f
    val y = center.y
    val left = Offset(size.width * 0.19f, y)
    val right = Offset(size.width * 0.81f, y)
    drawLine(color, left, right, strokeWidth = stroke, cap = StrokeCap.Round)
    drawCircle(color, radius = r, center = left)
    drawCircle(color, radius = r, center = right)
}

/** дата — отметка на оси времени. */
private fun DrawScope.drawDate(color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val baseY = h * 0.76f
    drawLine(
        color,
        Offset(w * 0.16f, baseY),
        Offset(w * 0.84f, baseY),
        strokeWidth = stroke,
        cap = StrokeCap.Square,
    )
    drawLine(
        color,
        Offset(w * 0.5f, h * 0.22f),
        Offset(w * 0.5f, baseY),
        strokeWidth = stroke,
        cap = StrokeCap.Square,
    )
}

/** мысль — та же точка, но полая: содержания у неё пока нет. */
private fun DrawScope.drawThought(color: Color, stroke: Float) {
    drawCircle(
        color,
        radius = size.minDimension * 0.28f,
        center = center,
        style = Stroke(width = stroke),
    )
}

/** факт — знание, зажатое между скобок: его не делают, его помнят. */
private fun DrawScope.drawFact(color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val top = h * 0.24f
    val bottom = h * 0.76f
    drawLine(color, Offset(w * 0.18f, top), Offset(w * 0.18f, bottom), stroke, StrokeCap.Square)
    drawLine(color, Offset(w * 0.82f, top), Offset(w * 0.82f, bottom), stroke, StrokeCap.Square)
    drawCircle(color, radius = size.minDimension * 0.11f, center = center)
}

/**
 * решение — развилка, у которой одна ветвь выбрана: две линии расходятся,
 * продолжена одна. Не галочка: галочка означала бы «сделано», а решение —
 * это выбор, а не выполнение.
 */
private fun DrawScope.drawDecision(color: Color, stroke: Float) {
    val w = size.width
    val h = size.height
    val fork = Offset(w * 0.5f, h * 0.5f)
    drawLine(color, Offset(w * 0.5f, h * 0.84f), fork, stroke, StrokeCap.Square)
    // Отброшенная ветвь короче и обрывается — путь, по которому не пошли.
    drawLine(color, fork, Offset(w * 0.26f, h * 0.32f), stroke, StrokeCap.Square)
    drawLine(color, fork, Offset(w * 0.8f, h * 0.16f), stroke, StrokeCap.Square)
}

@Preview(showBackground = true, backgroundColor = 0xFFFBF6F0)
@Composable
private fun TypeGlyphsPreview() {
    PrinyalTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(20.dp),
        ) {
            ItemType.entries.forEach { TypeGlyph(it, size = 28.dp) }
        }
    }
}
