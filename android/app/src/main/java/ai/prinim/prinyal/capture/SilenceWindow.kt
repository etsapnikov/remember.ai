package ai.prinim.prinyal.capture

/**
 * Сколько молчания ждать до авто-стопа.
 *
 * Раньше окно было одно на все случаи — две секунды. Для «купить молока» это
 * верно, для сложной мысли нет: человек берёт паузу, чтобы подумать, и запись
 * обрывается на середине.
 *
 * Цена ошибок несимметрична. Поздний стоп стоит нескольких секунд тишины в
 * конце аудио — их съест распознавание, никто не заметит. Ранний стоп стоит
 * потерянной мысли, и заметит её человек, а не мы. Поэтому окно смещено в
 * сторону долгого ожидания и **растёт по ходу записи**: чем дольше человек уже
 * говорит, тем больше похоже, что он думает вслух, а не закончил.
 *
 * Считаем не от общего времени записи, а от **наговоренного**: минута тишины в
 * начале не должна давать право на шестисекундную паузу.
 */
object SilenceWindow {

    /** Насколько терпеливым человек просит быть (настройка «Сколько ждать паузу»). */
    enum class Patience(val wire: Int, val factor: Float) {
        SHORT(0, 0.6f),
        NORMAL(1, 1.0f),
        LONG(2, 1.5f),
        ;

        companion object {
            fun of(wire: Int): Patience = entries.firstOrNull { it.wire == wire } ?: NORMAL
        }
    }

    // Опорные точки роста окна (токены capture.silenceWait).
    private const val SHORT_SPEECH_MS = 10_000L
    private const val MID_SPEECH_MS = 20_000L
    private const val LONG_SPEECH_MS = 45_000L

    private const val SHORT_WAIT_MS = 2_500L
    private const val MID_WAIT_MS = 4_000L
    private const val LONG_WAIT_MS = 6_000L

    /** Первые миллисекунды тишины копятся молча: это пауза между словами. */
    const val GRACE_MS = 400L

    /** Хвост отсчёта, который уже нельзя перебить. */
    const val LOCK_MS = 200L

    /**
     * Во сколько раз громче порога должен быть звук, чтобы считаться речью.
     *
     * Порог один и тот же и на вход в тишину, и на выход из неё давал мигание:
     * дыхание на самой границе то сбрасывало отсчёт, то нет. Разведённые пороги
     * убирают дребезг — уйти в тишину легко, вернуться нужно отчётливо.
     */
    private const val SPEECH_HYSTERESIS = 1.3f

    /**
     * @param voicedMs сколько всего наговорено в этой записи
     * @return сколько тишины ждать до остановки
     */
    fun waitMs(voicedMs: Long, patience: Patience = Patience.NORMAL): Long {
        val base = when {
            voicedMs <= SHORT_SPEECH_MS -> SHORT_WAIT_MS
            voicedMs >= LONG_SPEECH_MS -> LONG_WAIT_MS
            voicedMs <= MID_SPEECH_MS -> lerp(
                voicedMs, SHORT_SPEECH_MS, MID_SPEECH_MS, SHORT_WAIT_MS, MID_WAIT_MS,
            )
            else -> lerp(
                voicedMs, MID_SPEECH_MS, LONG_SPEECH_MS, MID_WAIT_MS, LONG_WAIT_MS,
            )
        }
        return (base * patience.factor).toLong()
    }

    /**
     * Речь сейчас или тишина, с гистерезисом.
     *
     * @param wasSpeech что решили на прошлом тике — в «серой зоне» между порогами
     *                  состояние не меняется
     */
    fun isSpeech(amplitude: Int, threshold: Int, wasSpeech: Boolean): Boolean = when {
        amplitude >= threshold * SPEECH_HYSTERESIS -> true
        amplitude <= threshold -> false
        else -> wasSpeech
    }

    private fun lerp(x: Long, x0: Long, x1: Long, y0: Long, y1: Long): Long =
        y0 + (y1 - y0) * (x - x0) / (x1 - x0)
}
