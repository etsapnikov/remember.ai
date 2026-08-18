package ai.prinim.prinyal

import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.domain.ReturnPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnPolicyTest {

    @Test
    fun `решение не возвращается даже с датой`() {
        // Главный случай Р-15.10: дата у решения есть и должна быть видна, но
        // уведомление о принятом решении — это шум.
        DueKind.entries.forEach { due ->
            assertFalse("$due", ReturnPolicy.schedules(ItemType.DECISION, due))
        }
    }

    @Test
    fun `дело с окном или датой возвращается`() {
        assertTrue(ReturnPolicy.schedules(ItemType.DO, DueKind.WINDOW))
        assertTrue(ReturnPolicy.schedules(ItemType.BUY, DueKind.EXACT))
        assertTrue(ReturnPolicy.schedules(ItemType.TELL, DueKind.WINDOW))
    }

    @Test
    fun `без срока возврата нет`() {
        assertFalse(ReturnPolicy.schedules(ItemType.FACT, DueKind.NONE))
        assertFalse(ReturnPolicy.schedules(ItemType.DO, DueKind.NONE))
    }
}
