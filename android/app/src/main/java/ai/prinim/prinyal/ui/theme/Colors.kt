package ai.prinim.prinyal.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Палитра из `assets/design-tokens.json`. Правки — только здесь и в токенах.
 *
 * Семантика (токены, раздел `semantics`) — не рекомендация, а правило:
 *  - [record] — только клавиша записи, её пульс и таймер записи;
 *  - [accentSelf] — всё, что продукт сделал сам: планы, возвраты, квитанция, причина;
 *  - [done] — только состояние done у айтема;
 *  - красный вне жеста записи, зелёный вне done и синий где угодно — запрещены.
 */
@Immutable
data class PrinyalColors(
    val paper: Color,
    val surface: Color,
    val wellSurface: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val rule: Color,
    val accentSelf: Color,
    val accentSelfSoft: Color,
    val record: Color,
    val recordPressed: Color,
    val recordWell: Color,
    val done: Color,
    val isDark: Boolean,
)

val LightColors = PrinyalColors(
    paper = Color(0xFFFBF6F0),
    surface = Color(0xFFFFFFFF),
    wellSurface = Color(0xFFEFE6DC),
    ink = Color(0xFF241C17),
    inkMuted = Color(0xFF6E5F55),
    inkFaint = Color(0xFF8C7A6E),
    rule = Color(0xFFE2D3C6),
    accentSelf = Color(0xFFB4552F),
    accentSelfSoft = Color(0xFFFBF3EC),
    record = Color(0xFFC8362A),
    recordPressed = Color(0xFFAE2A20),
    recordWell = Color(0xFF8E2318),
    done = Color(0xFF3F6B4F),
    isDark = false,
)

/** Тёмная тема — не инверсия, а самостоятельная проработка (ТЗ UI §5, ТЗ айдентики §6). */
val DarkColors = PrinyalColors(
    paper = Color(0xFF1A1512),
    surface = Color(0xFF221C18),
    wellSurface = Color(0xFF2C241E),
    ink = Color(0xFFF3EBE3),
    inkMuted = Color(0xFFA9998C),
    inkFaint = Color(0xFF8C7A6E),
    rule = Color(0xFF372D26),
    accentSelf = Color(0xFFD98F6B),
    accentSelfSoft = Color(0xFF2A1F18),
    record = Color(0xFFD8412F),
    recordPressed = Color(0xFFB32C20),
    recordWell = Color(0xFF7A1C12),
    done = Color(0xFF7FB08F),
    isDark = true,
)
