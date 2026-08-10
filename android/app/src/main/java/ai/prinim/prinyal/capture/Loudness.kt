package ai.prinim.prinyal.capture

import kotlin.math.log10

/**
 * Перевод сырой громкости `MediaRecorder.maxAmplitude` (0..32767) в уровень 0..1
 * для кольца записи (спека R1.2 §13).
 *
 * Линейная нормировка (`amplitude / 12000`) не годится: реальный пик речи на
 * телефоне владельца — 3480 из 32767, кольцо росло на два пикселя и выглядело
 * мёртвым. Громкость воспринимается логарифмически, поэтому переводим в дБFS и
 * растягиваем рабочий диапазон [QUIET_DBFS, LOUD_DBFS] на весь ход: тихая комната
 * остаётся у нуля, разговорная речь занимает верхнюю половину.
 */
object Loudness {

    /** Тише этого — тишина: кольцо не дышит. */
    const val QUIET_DBFS = -36f

    /** Громче этого — полный ход кольца. */
    const val LOUD_DBFS = -6f

    private const val FULL_SCALE = 32_767f

    fun level(amplitude: Int): Float {
        if (amplitude <= 0) return 0f
        val dbfs = 20f * log10(amplitude / FULL_SCALE)
        return ((dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0f, 1f)
    }
}
