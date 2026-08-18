package ai.prinim.prinyal

import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.domain.StuckPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Развилка застрявших. Здесь проверяется мера: четвёртое одинаковое напоминание
 * ничему не помогает, а только учит человека отмахиваться от продукта.
 */
class StuckPolicyTest {

    private var seq = 0

    private fun ret(action: String?, fired: Boolean = true) = ReturnEntity(
        id = "r${seq++}",
        itemId = "i1",
        scheduledAt = 0,
        firedAt = if (fired) 1 else null,
        action = action,
        attempt = 1,
    )

    @Test
    fun `третий перенос рождает развилку, второй — нет`() {
        val two = listOf(ret("later"), ret("later"))
        assertFalse(StuckPolicy.isFork(ItemState.PLANNED, two))

        val three = two + ret("later")
        assertTrue(StuckPolicy.isFork(ItemState.PLANNED, three))
    }

    @Test
    fun `показы без ответа переносами не считаются`() {
        // Пропущенное уведомление — про то, что человек не заметил, и лечится
        // другим. Перенос — осознанное «не сейчас», сказанное трижды.
        val missed = listOf(ret(null), ret(null), ret(null), ret("miss"))
        assertFalse(StuckPolicy.isFork(ItemState.PLANNED, missed))
    }

    @Test
    fun `закрытое дело не разбирают`() {
        val three = listOf(ret("later"), ret("later"), ret("later"))
        listOf(ItemState.DONE, ItemState.DISMISSED, ItemState.EXPIRED).forEach { state ->
            assertFalse("$state попал в развилку", StuckPolicy.isFork(state, three))
        }
    }

    @Test
    fun `генерик-совет не проходит как малый шаг`() {
        val task = "разобрать гараж"
        listOf(
            "начни с малого",
            "Сделай первый шаг",
            "разбей на части",
            "составь план",
        ).forEach { junk ->
            assertFalse("прошёл генерик: $junk", StuckPolicy.isRealStep(junk, task))
        }
    }

    @Test
    fun `конкретный маленький шаг проходит`() {
        assertTrue(StuckPolicy.isRealStep("вынести коробки у входа", "разобрать гараж и подвал"))
    }

    @Test
    fun `пересказ вместо уменьшения не проходит`() {
        // Шаг длиннее исходного дела — это не уменьшение, а пересказ.
        assertFalse(
            StuckPolicy.isRealStep(
                "разобрать гараж полностью, включая подвал и антресоли, и вывезти всё",
                "разобрать гараж",
            )
        )
    }

    @Test
    fun `пустой и короткий шаг не проходят`() {
        assertFalse(StuckPolicy.isRealStep("", "разобрать гараж"))
        assertFalse(StuckPolicy.isRealStep("ок", "разобрать гараж"))
    }
}
