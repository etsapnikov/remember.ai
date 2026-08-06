package ai.prinim.prinyal.ui.components

import ai.prinim.prinyal.ui.theme.Gesture
import ai.prinim.prinyal.ui.theme.Motion
import ai.prinim.prinyal.ui.theme.Prinyal
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Строка ленты со свайп-зоной удаления (спека R1.1 §2.2).
 *
 * Правила из спеки, зашитые здесь:
 *  - зона 88 dp, липнет к пальцу 1:1, дальше ширины зоны не уезжает;
 *  - порог фиксации 40 dp или 400 dp/с, ниже — возврат пресетом follow-cancel;
 *  - сам свайп не удаляет — удаление только тапом по зоне (итого два касания);
 *  - открыта максимум одна зона на ленту — за это отвечает [openKey] снаружи;
 *  - красная пара destructive живёт только в открытом состоянии.
 *
 * Содержимое не сдвигается, а сжимается: контент получает ширину минус открытая
 * часть, и сам решает, что спрятать (в ленте прячется длительность).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SwipeRevealRow(
    key: String,
    openKey: String?,
    onOpen: (String?) -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (revealedFraction: Float) -> Unit,
) {
    val density = LocalDensity.current
    val actionWidth = Gesture.ROW_ACTION_WIDTH_DP.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val commitPx = with(density) { Gesture.SWIPE_COMMIT_ROW_DP.dp.toPx() }
    val velocityPx = with(density) { Gesture.SWIPE_COMMIT_ROW_VELOCITY.dp.toPx() }

    val state = remember(key) {
        AnchoredDraggableState(
            initialValue = false,
            anchors = DraggableAnchors {
                false at 0f
                true at -actionWidthPx
            },
            // Порог из токенов — в долях пути: 40 dp от 88 dp.
            positionalThreshold = { _ -> commitPx },
            velocityThreshold = { velocityPx },
            snapAnimationSpec = tween(
                Motion.FollowSettle.durationMs,
                easing = Motion.FollowSettle.easing,
            ),
            decayAnimationSpec = androidx.compose.animation.core.exponentialDecay(),
        )
    }

    // Снаружи открыли другую строку — эта закрывается (одновременно открыта одна).
    LaunchedEffect(openKey) {
        if (openKey != key && state.currentValue) {
            state.animateTo(false)
        }
    }
    LaunchedEffect(state.currentValue) {
        if (state.currentValue) onOpen(key) else if (openKey == key) onOpen(null)
    }

    val revealPx = (-state.requireOffset()).coerceIn(0f, actionWidthPx)
    val revealFraction = revealPx / actionWidthPx

    Box(modifier.fillMaxWidth()) {
        // Зона действия под контентом: видна ровно настолько, насколько открыт свайп.
        if (revealPx > 0.5f) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(with(density) { revealPx.toDp() })
                    .background(Prinyal.colors.destructiveBg)
                    .clickable(onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                if (revealFraction > 0.55f) {
                    Text(
                        text = actionLabel,
                        style = Prinyal.type.label,
                        color = Prinyal.colors.destructiveFg,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(end = with(density) { revealPx.toDp() })
                .anchoredDraggable(state, Orientation.Horizontal)
                .then(
                    // Тап по контенту при открытой зоне закрывает её, а не открывает
                    // запись: «зона закрывается тапом мимо».
                    if (state.currentValue) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpen(null) }
                    } else {
                        Modifier
                    }
                ),
        ) {
            content(revealFraction)
        }
    }
}
