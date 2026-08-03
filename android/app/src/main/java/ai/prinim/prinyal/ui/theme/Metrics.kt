package ai.prinim.prinyal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Шкала отступов из токенов (`space`). База 4, шаг не «на глаз». */
@Immutable
object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val sm: Dp = 12.dp
    val m: Dp = 16.dp
    val ml: Dp = 20.dp
    val l: Dp = 26.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 44.dp

    /** Поле экрана. Воздуха много — это про разгрузку, а не про плотность (ТЗ UI §2). */
    val screen: Dp = 26.dp
    val itemGap: Dp = 18.dp
}

/** Радиусы из токенов (`radius`). Лист почти не скруглён — он бумажный, не пластиковый. */
@Immutable
object Radius {
    val sheet = RoundedCornerShape(2.dp)
    val control = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(999.dp)
    val icon = RoundedCornerShape(20.dp)
}

/** Геометрия клавиши записи (`key`): корпус, колпачок, нажатый колпачок, точка. */
@Immutable
object KeyMetrics {
    val housing: Dp = 112.dp
    val cap: Dp = 84.dp
    val capPressed: Dp = 78.dp
    val dot: Dp = 26.dp
}

/**
 * Минимальная цель касания. Ключевые действия — в нижней трети экрана, управление
 * одной рукой (ТЗ UI §2, п. 6).
 */
val MinTouchTarget: Dp = 48.dp
