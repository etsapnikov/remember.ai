package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.PersonEntity
import ai.prinim.prinyal.data.PersonStatus

/**
 * Когда продукт вправе спросить «кто такая Юля» (Р-14.7, Д-5).
 *
 * Вся политика собрана в чистой функции намеренно: это правила про такт и меру,
 * а не про экран, и ошибиться в них дороже всего. Частые вопросы превращают
 * продукт в должника, который вместо помощи требует анкету.
 *
 * Четыре ограничения, каждое из своей опасности:
 *  - **со второго появления.** Первая встреча имени ничего не значит: люди
 *    мелькают в речи постоянно, и спрашивать про каждого — это допрос;
 *  - **не чаще раза в три дня**, независимо от числа непонятных имён;
 *  - **«не надо» навсегда.** Переспросить нельзя даже через месяц — отказ это
 *    ответ, а не отсрочка;
 *  - **никогда в момент записи.** Вопрос на пути «сказал → запомнил» ломает
 *    петлю ради любопытства.
 */
object AskPolicy {

    /** Реже нельзя даже при десятке непонятных имён. */
    const val COOLDOWN_DAYS = 3L
    private const val COOLDOWN_MS = COOLDOWN_DAYS * 24 * 60 * 60 * 1000

    /** Имя должно встретиться минимум дважды в разных записях. */
    const val MIN_SEEN = 2

    /**
     * @param candidate лучший кандидат на вопрос или null
     * @param lastAskedAt когда спрашивали в последний раз про кого угодно
     * @return можно ли спросить прямо сейчас
     */
    fun canAsk(candidate: PersonEntity?, lastAskedAt: Long?, now: Long): Boolean {
        val person = candidate ?: return false
        if (PersonStatus.of(person.status) != PersonStatus.UNKNOWN) return false
        if (person.seenCount < MIN_SEEN) return false
        // Молчание после отказа считается от последнего вопроса вообще, а не от
        // этого имени: иначе три незнакомых имени дали бы три вопроса подряд.
        if (lastAskedAt != null && now - lastAskedAt < COOLDOWN_MS) return false
        return true
    }
}
