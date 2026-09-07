package ai.prinim.prinyal.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Палитра полишинга 1.3 (ТЗ §2). Правки — только здесь.
 *
 * Семантика — не рекомендация, а правило:
 *  - [accentSelf] — только действие и статус возврата; декора акцентом не бывает;
 *  - [done] — только «сделано»;
 *  - [statusWarn] — только системное ограничение;
 *  - синего в продукте нет вовсе.
 *
 * Поверхностей на экране не больше двух: [paper] и [surface]. Третьей ступени
 * ([wellSurface]) в 1.3 не осталось — она совпадает с [surface] намеренно, чтобы
 * вложенный блок не заводил себе новый оттенок явочным порядком.
 *
 * Два значения светлой темы отличаются от таблицы §2, и это не описка.
 * ТЗ требует в §6 контраст не ниже 4.5:1 — а его же §2 задаёт служебный
 * `#8A7B6D` (3.74:1 на фоне, 3.37:1 на поверхности) и предупреждение
 * `#A9781A` (3.57:1). Две части одного документа несовместимы, и служебный
 * уровень — самый частый в продукте: им набраны время, счётчики, даты, планы,
 * глоссарий и вся дев-панель. Взят минимальный сдвиг светлоты, который проходит
 * §6 и не трогает тон: `#74675C` (5.01/4.51) и `#896115` (5.08/4.58).
 * Тёмная тема проходит §6 в исходных значениях и оставлена как в ТЗ.
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
    /** Нажатое состояние акцента. Без него кнопка «моргала» альфой (ТЗ §2). */
    val accentPressed: Color,
    /** Акцент 10% — подложка метки возврата и подсветки совпадения. */
    val accentSelfSoft: Color,
    /**
     * Текст поверх акцентной заливки. В тёмной теме он **тёмный**: акцент там
     * светлее фона, и инверсия убила бы контраст (ТЗ §2, прямой запрет).
     */
    val onAccent: Color,
    val record: Color,
    val recordPressed: Color,
    val recordWell: Color,
    val done: Color,
    /** Хайрлайн-разделитель ленты. В 1.3 совпадает с [rule]: разделитель один. */
    val hairline: Color,
    /**
     * Пара удаления — только в открытой зоне свайпа. В покое красного в продукте
     * нет (спека §2.3).
     */
    val destructiveBg: Color,
    val destructiveFg: Color,
    /** Статусы «Недели»: выше порога / ниже порога. */
    val statusOk: Color,
    val statusWarn: Color,
    val isDark: Boolean,
)

val LightColors = PrinyalColors(
    paper = Color(0xFFFAF4EC),
    surface = Color(0xFFF1E8DC),
    wellSurface = Color(0xFFF1E8DC),
    ink = Color(0xFF26201B),
    inkMuted = Color(0xFF4A3F36),
    inkFaint = Color(0xFF74675C),
    rule = Color(0x1426201B),
    accentSelf = Color(0xFFB4462A),
    accentPressed = Color(0xFF9E3B22),
    accentSelfSoft = Color(0x1AB4462A),
    onAccent = Color(0xFFFAF4EC),
    record = Color(0xFFB4462A),
    recordPressed = Color(0xFF9E3B22),
    recordWell = Color(0x1FB4462A),
    done = Color(0xFF2E7D5B),
    hairline = Color(0x1426201B),
    destructiveBg = Color(0x1AB4462A),
    destructiveFg = Color(0xFFB4462A),
    statusOk = Color(0xFF2E7D5B),
    statusWarn = Color(0xFF896115),
    isDark = false,
)

/** Тёмная тема — не инверсия, а самостоятельная проработка (ТЗ §2). */
val DarkColors = PrinyalColors(
    paper = Color(0xFF191411),
    surface = Color(0xFF241C18),
    wellSurface = Color(0xFF241C18),
    ink = Color(0xFFF2EAE0),
    inkMuted = Color(0xFFD8CCC0),
    inkFaint = Color(0xFFA08F80),
    rule = Color(0x17F2EAE0),
    accentSelf = Color(0xFFE08863),
    accentPressed = Color(0xFFD0754F),
    accentSelfSoft = Color(0x1AE08863),
    // Тёмный текст на светлом акценте: инвертировать нельзя (ТЗ §2).
    onAccent = Color(0xFF191411),
    record = Color(0xFFE08863),
    recordPressed = Color(0xFFD0754F),
    recordWell = Color(0x1FE08863),
    done = Color(0xFF7FCBA4),
    hairline = Color(0x17F2EAE0),
    destructiveBg = Color(0x1AE08863),
    destructiveFg = Color(0xFFE08863),
    statusOk = Color(0xFF7FCBA4),
    statusWarn = Color(0xFFE0B24E),
    isDark = true,
)

/**
 * Клавиша записи. В 1.3 она перестала быть «пластиковым предметом» с корпусом,
 * колпачком и точкой: в макете это один акцентный круг 132 px с ореолом 10 px и
 * квадратной меткой стоп цвета бумаги (ТЗ §5, экран 01/30).
 *
 * Прежняя трёхслойная модель осталась бы единственным местом продукта со своей
 * палитрой — а правило §2 говорит, что акцент один на всё действие.
 */
@Immutable
data class KeyPalette(
    val housing: Color,
    val housingHalo: Color,
    val mark: Color,
    val idleHousing: Color,
    val idleMark: Color,
)

val DarkKeyColors = KeyPalette(
    housing = Color(0xFFE08863),
    housingHalo = Color(0x1FE08863),
    mark = Color(0xFF191411),
    idleHousing = Color(0xFF241C18),
    idleMark = Color(0xFFE08863),
)

val LightKeyColors = KeyPalette(
    housing = Color(0xFFB4462A),
    housingHalo = Color(0x1FB4462A),
    mark = Color(0xFFFAF4EC),
    idleHousing = Color(0xFFF1E8DC),
    idleMark = Color(0xFFB4462A),
)
