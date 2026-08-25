package ai.prinim.prinyal.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable

/**
 * Моушн из токенов (`motion`). Движений ровно три плюс квитанция — больше в продукте
 * анимаций нет (ТЗ UI §4, ТЗ айдентики §7): движение = дыхание, не спецэффект.
 */
@Immutable
data class MotionSpec(
    val durationMs: Int,
    val easing: Easing,
)

object Motion {

    /**
     * Полишинг, п. 7–10: анимируется только то, что меняет геометрию под
     * пальцем. Всё остальное меняется мгновенно — задержка врёт про вес
     * действия.
     */
    object Polish {
        /** Раскрытие пункта и дня: соседние строки уезжают, и место теряется. */
        const val EXPAND_MS = 140

        /** Снекбар несёт «вернуть» — его появление обязано быть замечено. */
        const val SNACK_IN_MS = 160
        const val SNACK_OUT_MS = 120

        /** Смена фильтра: при похожих выдачах иначе непонятно, сменилось ли. */
        const val FILTER_MS = 120
    }

    /** Живой пульс во время записи: «слушаю». */
    val Pulse = MotionSpec(2200, CubicBezierEasing(.4f, 0f, .2f, 1f))
    val PulseMaxScale = 1.3f
    val PulseOpacity = 0.5f to 0f

    /** Квитанция «Принял.»: приходит быстро и уверенно. */
    val Receipt = MotionSpec(600, CubicBezierEasing(.2f, .8f, .2f, 1f))

    /** Уход заметки после квитанции — выдох. */
    val NoteAway = MotionSpec(2600, CubicBezierEasing(.4f, 0f, 1f, 1f))
    val NoteAwayTranslateY = -26f

    /** Похороны айтема: спокойный уход, без вины и драмы. */
    val Burial = MotionSpec(900, CubicBezierEasing(.3f, 0f, .6f, 1f))

    /** Долёт жеста до конца пути (переход запись↔лента, фиксация зоны свайпа). */
    val FollowSettle = MotionSpec(320, CubicBezierEasing(.2f, .8f, .2f, 1f))
    const val FollowSettleMinMs = 180

    /** Возврат прерванного жеста на место. */
    val FollowCancel = MotionSpec(200, CubicBezierEasing(.4f, 0f, .6f, 1f))

    /** Ход колпачка вниз под пальцем. */
    val KeyPress = MotionSpec(90, CubicBezierEasing(.4f, 0f, 1f, 1f))

    /** Отпускание: колпачок возвращается. Микрофон стартует здесь, не позже. */
    val KeyRelease = MotionSpec(160, CubicBezierEasing(.2f, .8f, .2f, 1f))

    /** Смена состояния клавиши: цвета, таймер, подпись. */
    val StateShift = MotionSpec(220, CubicBezierEasing(.4f, 0f, .2f, 1f))
    const val StateShiftRingDelayMs = 60

    /**
     * Кольцо-амплитуда: быстрый подъём, медленный спад — так индикация читается
     * как дыхание речи, а не как дрожь (R1.2 §13).
     */
    const val AmpAttackMs = 100
    const val AmpReleaseTauMs = 220
}

/**
 * Пороги жестов из токенов (`gesture`). Смещения в dp, скорости в dp/с.
 */
object Gesture {
    const val SWIPE_START_DP = 8
    const val SWIPE_COMMIT_VERTICAL_DP = 96
    const val SWIPE_COMMIT_VERTICAL_VELOCITY = 600
    const val SWIPE_COMMIT_ROW_DP = 40
    const val SWIPE_COMMIT_ROW_VELOCITY = 400
    const val ROW_ACTION_WIDTH_DP = 88
}
