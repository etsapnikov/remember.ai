package ai.prinim.prinyal.ui.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Вибро-айдентика из токенов (`haptics`).
 *
 * Квитанция обязана узнаваться вслепую, телефоном в кармане (ТЗ айдентики §5, §9.3) —
 * поэтому это не системный «клик», а свой паттерн, и менять его можно только вместе
 * с токенами.
 */
object Haptics {

    /** Квитанция «Принял.»: двойной короткий — 18 мс, пауза 70 мс, 34 мс. */
    // Один тик, а не два: токен haptics.receipt всегда описывал одиночный, а в
    // коде жил двойной (аудит Д-7). Квитанция — знак спокойной завершённости,
    // и двойной тик читается как «что-то ещё случилось».
    private val RECEIPT_PATTERN = longArrayOf(0, 18)
    private val RECEIPT_AMPLITUDES = intArrayOf(0, 255)

    /** Отмена записи — один 40 мс: заметно тяжелее квитанции, их не спутать. */
    private const val CANCEL_MS = 40L

    /** Действие в возврате — один 12 мс: почти незаметное подтверждение. */
    private const val RETURN_ACTION_MS = 12L

    fun receipt(context: Context) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(RECEIPT_PATTERN, RECEIPT_AMPLITUDES, -1)
            } else {
                VibrationEffect.createWaveform(RECEIPT_PATTERN, -1)
            }
        )
    }

    fun cancel(context: Context) = oneShot(context, CANCEL_MS, 180)

    fun returnAction(context: Context) = oneShot(context, RETURN_ACTION_MS, 120)

    private fun oneShot(context: Context, ms: Long, amplitude: Int) {
        val vibrator = vibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
