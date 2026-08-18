package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ReturnEntity

/**
 * Когда пункт пора не напоминать, а разбирать (Р-15.8).
 *
 * Третий перенос — это не забывчивость, а сигнал: дело в нынешнем виде не
 * делается. Четвёртое одинаковое напоминание ничего не изменит, оно только
 * научит человека отмахиваться от продукта.
 *
 * Поэтому на третьем переносе возврат приходит не напоминанием, а выбором:
 * уменьшить, переформулировать или похоронить. Все три — действия. Диагнозов
 * («ты застрял», «это давно висит») продукт не ставит: он видит перенос, а не
 * причину, и говорить о причине значит выдумывать.
 *
 * Считаем именно **переносы**, а не показы. Показ без ответа — это про то, что
 * человек не заметил уведомление, и лечится другим. Перенос — это осознанное
 * «не сейчас», сказанное трижды.
 */
object StuckPolicy {

    /** Со скольких переносов возврат превращается в развилку. */
    const val SNOOZES_TO_FORK = 3

    /** Что предлагаем вместо очередного напоминания. */
    enum class Way { SHRINK, REPHRASE, BURY }

    fun snoozeCount(returns: List<ReturnEntity>): Int =
        returns.count { it.action == "later" }

    /**
     * @param state текущее состояние пункта
     * @param returns все возвраты этого пункта
     * @return нужно ли показать развилку вместо обычного возврата
     */
    fun isFork(state: ItemState, returns: List<ReturnEntity>): Boolean {
        // Закрытое не разбирают: человек уже ответил, и предлагать ему
        // «уменьшить» сделанное — то же самое, что не слушать.
        if (state !in setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)) {
            return false
        }
        return snoozeCount(returns) >= SNOOZES_TO_FORK
    }

    /**
     * Годится ли предложенный малый шаг.
     *
     * Генерик вроде «начни с малого» — брак: он ничего не сообщает и выдаёт,
     * что модель не поняла задачу. Настоящий шаг говорит, что сделать руками
     * в ближайшие минуты, и словами самого человека.
     */
    fun isRealStep(step: String, original: String): Boolean {
        val clean = step.trim()
        if (clean.length < MIN_STEP_CHARS) return false
        if (GENERIC.any { clean.lowercase().contains(it) }) return false
        // Шаг обязан быть меньше исходного дела: если он его длиннее, это
        // пересказ, а не уменьшение.
        return clean.length <= original.trim().length + SLACK_CHARS
    }

    private const val MIN_STEP_CHARS = 8
    private const val SLACK_CHARS = 20

    /** Стоп-лист пустых советов — тот же, что в eval яруса 2. */
    private val GENERIC = listOf(
        "начни с малого",
        "сделай первый шаг",
        "разбей на части",
        "просто начни",
        "выдели время",
        "составь план",
    )
}
