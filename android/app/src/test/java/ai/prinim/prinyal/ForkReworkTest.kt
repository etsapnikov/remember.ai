package ai.prinim.prinyal

import ai.prinim.prinyal.domain.StuckPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Замена формулировки застрявшего дела. Проверяется то, что модель НЕ вправе
 * подсунуть: подменить слова человека отговоркой хуже, чем не сделать ничего.
 */
class ForkReworkTest {

    private val task = "разобрать гараж и вывезти всё лишнее"

    @Test
    fun `малый шаг — подмножество исходного дела`() {
        // Приёмка ТЗ буквально: шаг для «разобрать гараж» — часть той же работы.
        assertTrue(StuckPolicy.isRealStep("вынести коробки у входа гаража", task))
        assertTrue(StuckPolicy.isRealStep("разобрать один стеллаж", task))
    }

    @Test
    fun `отговорки не проходят ни в одной форме`() {
        listOf(
            "начни с малого",
            "Сделай первый шаг сегодня",
            "разбей на части",
            "составь план уборки",
            "просто начни",
            "выдели время в выходные",
        ).forEach { assertFalse("прошла отговорка: $it", StuckPolicy.isRealStep(it, task)) }
    }

    @Test
    fun `пересказ длиннее исходного уменьшением не считается`() {
        assertFalse(
            StuckPolicy.isRealStep(
                "разобрать гараж полностью, включая подвал, антресоли и всё лишнее вывезти",
                task,
            )
        )
    }
}
