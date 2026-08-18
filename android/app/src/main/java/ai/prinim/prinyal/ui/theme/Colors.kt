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
    /** Хайрлайн-разделитель ленты — тоньше и тише обычного rule. */
    val hairline: Color,
    /**
     * Пара удаления. Единственная красная пара после клавиши записи, и появляется
     * только в открытой зоне свайпа — в покое красного в продукте нет (спека §2.3).
     */
    val destructiveBg: Color,
    val destructiveFg: Color,
    /** Статусы «Недели»: выше порога / ниже порога. */
    val statusOk: Color,
    val statusWarn: Color,
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
    hairline = Color(0xFFF0E6DB),
    destructiveBg = Color(0xFFF6E0D9),
    destructiveFg = Color(0xFFB03A24),
    statusOk = Color(0xFF3F6B4F),
    // Предупреждение и «продукт сделал сам» были почти одного цвета — #A8542B
    // против accentSelf #B4552F, глазом неразличимо. Разведены на два шага
    // светлоты в обе стороны (аудит Д-7): предупреждение уходит в жёлто-охряное,
    // подальше от красно-оранжевого акцента.
    statusWarn = Color(0xFF8A6415),
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
    hairline = Color(0xFF1E1917),
    destructiveBg = Color(0xFF3C1E18),
    destructiveFg = Color(0xFFE8836B),
    statusOk = Color(0xFF8FB79A),
    statusWarn = Color(0xFFD9A441),
    isDark = true,
)

/**
 * Цвета клавиши записи (спека R1.2 §12).
 *
 * Раньше они были заданы абсолютными «для обеих тем»: клавиша — физический
 * предмет, её пластик не перекрашивается вслед за фоном. Логика верная, вывод
 * оказался неверным. На кремовом фоне светлой темы тёмно-бордовый корпус стал
 * единственным тёмным пятном на экране, и клавиша читалась как **выключенная**
 * (аудит Д-7, п. 10).
 *
 * Предмет и правда один, но освещение разное: в светлой теме корпус светлый, а
 * красным остаётся колпачок — то есть сохраняется ровно то, что и делало
 * клавишу клавишей.
 */
@Immutable
data class KeyPalette(
    val idleHousing: Color,
    val idleHousingEdge: Color,
    val idleCap: Color,
    val idleDot: Color,
    val idleTimer: Color,
    val recHousing: Color,
    val recHousingEdge: Color,
    val recCap: Color,
    val recStopMark: Color,
    val recTimer: Color,
    val ring: Color,
    val ringIdle: Color,
)

val DarkKeyColors = KeyPalette(
    idleHousing = Color(0xFF1E1917),
    idleHousingEdge = Color(0xFF2E2724),
    idleCap = Color(0xFF2A211E),
    idleDot = Color(0xFFD8402F),
    idleTimer = Color(0xFF4A403A),
    recHousing = Color(0xFF2A1512),
    recHousingEdge = Color(0xFF52201A),
    recCap = Color(0xFFD8402F),
    recStopMark = Color(0xFF2A1512),
    recTimer = Color(0xFF8C8078),
    ring = Color(0xFFE8836B),
    ringIdle = Color(0xFF4A403A),
)

/** Тройка для светлой темы из токенов: record, recordWell, wellSurface. */
val LightKeyColors = KeyPalette(
    idleHousing = Color(0xFFEFE6DC),
    idleHousingEdge = Color(0xFFE2D3C6),
    idleCap = Color(0xFFE2D3C6),
    idleDot = Color(0xFFC8362A),
    idleTimer = Color(0xFF8C7A6E),
    recHousing = Color(0xFFEFE6DC),
    recHousingEdge = Color(0xFFE2D3C6),
    recCap = Color(0xFFC8362A),
    recStopMark = Color(0xFFEFE6DC),
    recTimer = Color(0xFF6E5F55),
    ring = Color(0xFF8E2318),
    ringIdle = Color(0xFF8C7A6E),
)
