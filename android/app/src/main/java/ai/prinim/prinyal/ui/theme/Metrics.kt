package ai.prinim.prinyal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Лестница отступов (ТЗ §1). База 4, и ни одного шага «на глаз».
 *
 * Разрешённые значения — 4, 8, 10, 12, 14, 16, 18, 20, 24, 32. Всё, что экран
 * хочет поставить между ними, — признак того, что блок стоит не там.
 *
 * Ритм: блок ↔ блок — [m], внутри блока — [s10]…[sm], группа ↔ её заголовок — [s].
 */
@Immutable
object Space {
    /** Текст и его собственная мета-строка — они одно целое. */
    val xs: Dp = 4.dp

    /** Заголовок и то, что под ним. */
    val s: Dp = 8.dp

    /** Пункты записи в столбце. */
    val s10: Dp = 10.dp

    /** Однородные элементы внутри группы. */
    val sm: Dp = 12.dp

    /** Внутренний верхний паддинг карточки. */
    val s14: Dp = 14.dp

    /** Блок ↔ блок. */
    val m: Dp = 16.dp

    /** Блоки шторки. */
    val s18: Dp = 18.dp

    /** Поле экрана — 20 на всех поверхностях, включая шторки и карточки (ТЗ §1). */
    val screen: Dp = 20.dp

    /** Смысловые части экрана: шапка ↔ содержимое. */
    val ml: Dp = 24.dp

    /** Разрыв между группами дат в ленте. */
    val l: Dp = 32.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 32.dp
    val itemGap: Dp = 10.dp
}

/** Радиусы (ТЗ §4). Каждый привязан к компоненту, «примерно такого же» не бывает. */
@Immutable
object Radius {
    /** Шторка правки — 28 сверху. */
    val sheet = RoundedCornerShape(28.dp)
    /** Поле поиска. */
    val field = RoundedCornerShape(16.dp)
    /** Иконка таб-бара 44×44. */
    val icon = RoundedCornerShape(14.dp)
    /** Метка статуса. */
    val badge = RoundedCornerShape(8.dp)
    /** Карточка вопроса «Спрашиваю». */
    val card = RoundedCornerShape(20.dp)
    /** Карточки «Недели». */
    val cardLarge = RoundedCornerShape(24.dp)
    /** Чип фильтра (36) и чип выбора в шторке (44). */
    val chip = RoundedCornerShape(18.dp)
    val chipTall = RoundedCornerShape(22.dp)
    /** Первичная кнопка 48 и крупное действие шторки 56. */
    val button = RoundedCornerShape(24.dp)
    val buttonTall = RoundedCornerShape(28.dp)
    val control = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(999.dp)
}

/**
 * Фиксированные высоты (ТЗ §4). Одинаковые по смыслу строки обязаны совпадать
 * по высоте на всех экранах — это первый пункт приёмки, и держится он только
 * тем, что число живёт в одном месте.
 */
@Immutable
object Sizes {
    val tabBar: Dp = 56.dp
    val tabGap: Dp = 13.dp
    val navIcon: Dp = 44.dp

    val filterRow: Dp = 52.dp
    val filterChip: Dp = 36.dp

    /** Строки списков. */
    val rowTopic: Dp = 68.dp
    val rowPerson: Dp = 58.dp
    val rowSetting: Dp = 56.dp
    val rowGlossary: Dp = 52.dp

    /** Кнопки. */
    val buttonPrimary: Dp = 48.dp
    val buttonSecondary: Dp = 48.dp
    val buttonTertiary: Dp = 44.dp
    val buttonSheet: Dp = 56.dp
    val buttonAllow: Dp = 52.dp

    /** Метка статуса. */
    val badge: Dp = 30.dp
    /** Заголовок группы. */
    val groupHeader: Dp = 38.dp
    /** Поле поиска. */
    val searchField: Dp = 52.dp
    /** Чип выбора в шторке правки. */
    val sheetChip: Dp = 44.dp
    /** Ручка шторки. */
    val sheetHandleWidth: Dp = 44.dp
    val sheetHandleHeight: Dp = 5.dp

    /** Переключатель. */
    val switchWidth: Dp = 52.dp
    val switchHeight: Dp = 32.dp
    val switchThumb: Dp = 26.dp

    /** Колонка даты в «Днях». */
    val dayDateColumn: Dp = 56.dp

    /** Вертикальная линия слева от пунктов записи. */
    val itemRule: Dp = 2.dp
    val itemRuleGap: Dp = 14.dp
}

/** Геометрия клавиши записи (ТЗ §5, экран 01/30). */
@Immutable
object KeyMetrics {
    val housing: Dp = 132.dp
    val halo: Dp = 10.dp
    val housingRadius: Dp = 44.dp
    val mark: Dp = 40.dp
    val markRadius: Dp = 10.dp
    /** Ход клавиши вниз при нажатии. */
    val capDrop: Dp = 3.dp
    /** Индикатор уровня над клавишей. */
    val levelBar: Dp = 4.dp
    val levelHeight: Dp = 44.dp
}

/**
 * Минимальная цель касания. Приёмка §6: ни одной цели меньше 44 — включая
 * «отмена» и «не спрашивать», которые исторически были голым текстом.
 */
val MinTouchTarget: Dp = 44.dp
