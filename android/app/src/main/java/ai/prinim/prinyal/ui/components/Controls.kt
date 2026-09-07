package ai.prinim.prinyal.ui.components

import ai.prinim.prinyal.ui.theme.MinTouchTarget
import ai.prinim.prinyal.ui.theme.Prinyal
import ai.prinim.prinyal.ui.theme.Radius
import ai.prinim.prinyal.ui.theme.Sizes
import ai.prinim.prinyal.ui.theme.Space
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Общие органы управления полишинга 1.3 (ТЗ §4).
 *
 * Смысл файла — не в переиспользовании, а в приёмке: два пункта из семи
 * («ни одной цели меньше 44», «одинаковые по смыслу строки одинаковой высоты»)
 * невозможно удержать, пока каждая высота живёт в своём экране. Здесь они
 * заданы один раз, и экран не может назначить кнопке свой рост.
 */

/** Первичная: 48, радиус 24, акцентная заливка, текст 16 Bold. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = Sizes.buttonPrimary,
    shape: RoundedCornerShape = Radius.button,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        if (pressed) Prinyal.colors.accentPressed else Prinyal.colors.accentSelf,
        tween(90), label = "primaryBg",
    )
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(if (enabled) bg else Prinyal.colors.surface)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.ml),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Prinyal.type.label,
            color = if (enabled) Prinyal.colors.onAccent else Prinyal.colors.inkFaint,
            maxLines = 1,
        )
    }
}

/** Вторичная: 48, обводка, прозрачный фон. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .height(Sizes.buttonSecondary)
            .clip(Radius.button)
            .background(if (pressed) Prinyal.colors.surface else Color.Transparent)
            .border(
                BorderStroke(1.dp, Prinyal.colors.ink.copy(alpha = if (Prinyal.colors.isDark) 0.22f else 0.16f)),
                Radius.button,
            )
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.screen),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = Prinyal.type.label,
            color = if (enabled) Prinyal.colors.ink else Prinyal.colors.inkFaint,
            maxLines = 1,
        )
    }
}

/**
 * Третичная: 44, без рамки, с подложкой при нажатии.
 *
 * Именно она заменила «слова с обработчиком»: «отмена», «не надо», «Заново» —
 * все они теперь честная цель 44, а не прямоугольник глифов.
 */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
    enabled: Boolean = true,
    mono: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .heightIn(min = Sizes.buttonTertiary)
            .clip(Radius.control)
            .background(if (pressed) Prinyal.colors.surface else Color.Transparent)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (mono) Prinyal.type.meta else Prinyal.type.label,
            color = color ?: Prinyal.colors.ink,
            maxLines = 1,
        )
    }
}

/** Крупное действие в шторке: 56, радиус 28. */
@Composable
fun SheetButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    PrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        height = Sizes.buttonSheet,
        shape = Radius.buttonTall,
    )

/**
 * Метка статуса: высота 30, радиус 8, моно 13 на подложке.
 *
 * Голым цветным текстом статусы больше не показываем (ТЗ §4): на живых снимках
 * «напомню вечером» акцентным текстом читалось как ссылка, а «2 сделано»
 * зелёным — как ошибка ввода.
 */
@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.Accent,
) {
    val fg = when (tone) {
        BadgeTone.Accent -> Prinyal.colors.accentSelf
        BadgeTone.Done -> Prinyal.colors.done
        BadgeTone.Warn -> Prinyal.colors.statusWarn
        BadgeTone.Muted -> Prinyal.colors.inkFaint
    }
    Box(
        modifier
            .height(Sizes.badge)
            .clip(Radius.badge)
            .background(if (tone == BadgeTone.Muted) Prinyal.colors.surface else fg.copy(alpha = 0.10f))
            .padding(horizontal = Space.s10),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = Prinyal.type.meta, color = fg, maxLines = 1)
    }
}

enum class BadgeTone { Accent, Done, Warn, Muted }

/**
 * Заголовок группы: моно 12 капсом с трекингом, разделитель сверху.
 *
 * Капс делается здесь, а не в строковых ресурсах: перевод не должен зависеть от
 * того, вспомнил ли экран написать заглавными.
 */
@Composable
fun GroupHeader(
    text: String,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) Divider()
        Box(
            Modifier
                .fillMaxWidth()
                .height(Sizes.groupHeader),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = text.uppercase(),
                style = Prinyal.type.groupLabel,
                color = Prinyal.colors.inkFaint,
                maxLines = 1,
            )
        }
    }
}

/** Разделитель. Между однородными строками — он, а не отступ (ТЗ §4). */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Prinyal.colors.rule)
    )
}

/**
 * Строка списка с фиксированной высотой: слева название, справа счётчик моно.
 *
 * Высота приходит снаружи ([Sizes.rowTopic], [Sizes.rowPerson] и т.д.) и внутри
 * строки не обсуждается — иначе длинное название начнёт растить свою строку и
 * список поплывёт, ровно как на снимках 1.3.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    counter: String? = null,
    height: androidx.compose.ui.unit.Dp = Sizes.rowTopic,
    titleColor: Color? = null,
    counterColor: Color? = null,
    divider: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = Space.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Prinyal.type.listRow,
                color = titleColor ?: Prinyal.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = true),
            )
            if (counter != null) {
                Box(Modifier.width(Space.sm))
                Text(
                    text = counter,
                    style = Prinyal.type.meta,
                    color = counterColor ?: Prinyal.colors.inkFaint,
                    maxLines = 1,
                )
            }
        }
        if (divider) Divider()
    }
}

/**
 * Строка фильтров (ТЗ §4).
 *
 * Прокрутка здесь не украшение: пять чипов не помещаются в 393 px, и до 1.3
 * последний («похоронено») уезжал под край экрана — на снимке 02 это видно
 * прямо. Чипы не сжимаются намеренно: сжатие сделало бы «в плане» и «сделано»
 * разной ширины при одинаковой роли.
 */
@Composable
fun FilterRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier
            .fillMaxWidth()
            .height(Sizes.filterRow)
            .horizontalScroll(scroll)
            .padding(horizontal = Space.screen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        options.forEachIndexed { i, label ->
            FilterChip(label = label, selected = i == selectedIndex, onClick = { onSelect(i) })
        }
    }
}

/** Чип фильтра: 36, радиус 18. Активный — инверсия, остальные — поверхность. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) Prinyal.colors.ink else Prinyal.colors.surface,
        tween(120), label = "chipBg",
    )
    val fg by animateColorAsState(
        if (selected) Prinyal.colors.paper else Prinyal.colors.inkMuted,
        tween(120), label = "chipFg",
    )
    Box(
        Modifier
            // Чип 36 сам по себе ниже нормы касания. Цель добирается высотой
            // строки: 52 вокруг центра — этого хватает, и чип остаётся 36.
            .height(Sizes.filterChip)
            .clip(Radius.chip)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.s14),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = Prinyal.type.itemTitle, color = fg, maxLines = 1)
    }
}

/** Чип выбора в шторке правки: 44, радиус 22, выбранный — акцентная заливка. */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val bg by animateColorAsState(
        if (selected) Prinyal.colors.accentSelf else Prinyal.colors.surface,
        tween(120), label = "choiceBg",
    )
    val fg by animateColorAsState(
        if (selected) Prinyal.colors.onAccent else Prinyal.colors.ink,
        tween(120), label = "choiceFg",
    )
    Row(
        modifier
            .height(Sizes.sheetChip)
            .clip(Radius.chipTall)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        leading?.invoke()
        Text(text = label, style = Prinyal.type.itemTitle, color = fg, maxLines = 1)
    }
}

/** Переключатель 52×32, бегунок 26, включённый — акцент. */
@Composable
fun PrinyalSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val track by animateColorAsState(
        if (checked) Prinyal.colors.accentSelf else Prinyal.colors.surface,
        tween(140), label = "track",
    )
    val offset by animateDpAsState(
        if (checked) Sizes.switchWidth - Sizes.switchThumb - 3.dp else 3.dp,
        tween(140), label = "thumb",
    )
    Box(
        modifier
            .size(width = Sizes.switchWidth, height = MinTouchTarget)
            .clickable(onClick = { onChange(!checked) }),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(width = Sizes.switchWidth, height = Sizes.switchHeight)
                .clip(RoundedCornerShape(Sizes.switchHeight / 2))
                .background(track)
        )
        Box(
            Modifier
                .padding(start = offset)
                .size(Sizes.switchThumb)
                .clip(RoundedCornerShape(Sizes.switchThumb / 2))
                .background(if (checked) Prinyal.colors.onAccent else Prinyal.colors.paper)
        )
    }
}

/** Пустое состояние: заголовок 22 ExtraBold + пояснение курсивом, без иллюстраций. */
@Composable
fun EmptyState(title: String, explain: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.screen, vertical = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = title,
            style = Prinyal.type.verdict.copy(fontSize = 22.sp),
            color = Prinyal.colors.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = explain,
            style = Prinyal.type.voice,
            color = Prinyal.colors.inkFaint,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Карточка на поверхности: вопрос «Спрашиваю», итог недели, блок действий. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = Radius.card,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Prinyal.colors.surface)
            .padding(Space.m),
        content = content,
    )
}
