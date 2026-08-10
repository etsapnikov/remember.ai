package ai.prinim.prinyal.ui.components

import ai.prinim.prinyal.ui.theme.Motion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlin.math.exp

/**
 * Сглаживание амплитуды для кольца записи (спека R1.2 §13).
 *
 * Сырой уровень приходит раз в 100 мс — если рисовать его как есть, кольцо
 * дёргается ступеньками. Быстрый подъём и медленный спад дают дыхание речи:
 *
 *  - атака (рост) — линейно за [Motion.AmpAttackMs];
 *  - спад — экспоненциально с постоянной [Motion.AmpReleaseTauMs].
 *
 * Считается **от системных часов по дельте времени**, не по числу кадров: на 60 и
 * 120 Гц поведение одинаковое.
 */
@Composable
fun rememberAmplitude(target: Float): State<Float> {
    val smoothed = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var previousNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (previousNanos != 0L) {
                    val deltaMs = (nanos - previousNanos) / 1_000_000f
                    val current = smoothed.floatValue
                    smoothed.floatValue = if (target > current) {
                        // Линейная атака: полный ход за AmpAttackMs.
                        (current + deltaMs / Motion.AmpAttackMs).coerceAtMost(target)
                    } else {
                        // Экспоненциальный спад к цели с постоянной τ.
                        val k = exp(-deltaMs / Motion.AmpReleaseTauMs)
                        target + (current - target) * k
                    }
                }
                previousNanos = nanos
            }
        }
    }
    return smoothed
}
