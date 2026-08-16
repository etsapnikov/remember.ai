package ai.prinim.prinyal

import ai.prinim.prinyal.data.PersonEntity
import ai.prinim.prinyal.data.PersonStatus
import ai.prinim.prinyal.domain.AskPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Такт доспроса. Здесь проверяется мера, а не механика: частый вопрос
 * превращает продукт в должника, который вместо помощи требует анкету.
 */
class AskPolicyTest {

    private val now = 1_786_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun person(
        seen: Int = 2,
        status: PersonStatus = PersonStatus.UNKNOWN,
    ) = PersonEntity(
        id = "p1",
        name = "Юля",
        nameNorm = "юля",
        status = status.wire,
        firstSeen = now - 5 * day,
        seenCount = seen,
    )

    @Test
    fun `первая встреча имени вопроса не рождает`() {
        // Люди мелькают в речи постоянно; спрашивать про каждого — допрос.
        assertFalse(AskPolicy.canAsk(person(seen = 1), lastAskedAt = null, now = now))
    }

    @Test
    fun `второе появление — можно спросить`() {
        assertTrue(AskPolicy.canAsk(person(seen = 2), lastAskedAt = null, now = now))
    }

    @Test
    fun `два вопроса в один день невозможны`() {
        assertFalse(AskPolicy.canAsk(person(), lastAskedAt = now - 60_000, now = now))
    }

    @Test
    fun `через три дня спрашивать снова можно`() {
        assertFalse(AskPolicy.canAsk(person(), lastAskedAt = now - 2 * day, now = now))
        assertTrue(AskPolicy.canAsk(person(), lastAskedAt = now - 4 * day, now = now))
    }

    @Test
    fun `отказ закрывает имя навсегда`() {
        // Даже через месяц: отказ это ответ, а не отсрочка.
        assertFalse(
            AskPolicy.canAsk(
                person(status = PersonStatus.DECLINED),
                lastAskedAt = now - 40 * day,
                now = now,
            )
        )
    }

    @Test
    fun `про известного человека не переспрашиваем`() {
        assertFalse(
            AskPolicy.canAsk(person(status = PersonStatus.KNOWN), lastAskedAt = null, now = now)
        )
    }

    @Test
    fun `некого спрашивать — вопроса нет`() {
        assertFalse(AskPolicy.canAsk(null, lastAskedAt = null, now = now))
    }
}
