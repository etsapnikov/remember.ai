package ai.prinim.prinyal

import ai.prinim.prinyal.domain.PersonIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имена. Здесь разделены два вопроса: что считать одним именем (решает код) и
 * что считать одним человеком (решает человек, продукт только спрашивает).
 */
class PersonIdentityTest {

    @Test
    fun `падежи одного имени дают один ключ`() {
        // Из-за этого доспрос и молчал две недели: «Юля» и «Юле» считались
        // разными строками, и счётчик не доходил до двух.
        val keys = listOf("Юля", "Юле", "Юлю", "юля").map(PersonIdentity::norm)
        assertEquals(1, keys.toSet().size)
    }

    @Test
    fun `спрашиваем про склейку только при совпавшей фамилии`() {
        assertTrue(PersonIdentity.mayBeSame("Саня Иванов", "Саша Иванов"))
        // Без фамилии догадка ничем не подкреплена: «Вера» и «Валера» похожи
        // ровно настолько, насколько похожи любые два русских имени.
        assertFalse(PersonIdentity.mayBeSame("Саня", "Саша"))
        assertFalse(PersonIdentity.mayBeSame("Вера", "Валера"))
    }

    @Test
    fun `однофамильцы с одинаковым именем вопроса не рождают`() {
        // Это одна и та же строка в разных падежах — склеит нормализация,
        // спрашивать нечего.
        assertFalse(PersonIdentity.mayBeSame("Эмиль Шакиров", "Эмилю Шакирову"))
    }

    @Test
    fun `разные фамилии не сравниваются`() {
        assertFalse(PersonIdentity.mayBeSame("Саня Иванов", "Саня Петров"))
    }

    @Test
    fun `имя ищется целым словом, а не основой`() {
        // Замер по живому корпусу дал «Вера — 13 заметок»; одиннадцать из них
        // оказались словами «верну» и «проверить». На таком сигнале раздел
        // «Люди» показывал бы выдумку.
        assertTrue(PersonIdentity.mentions("нужно маме вере написать", "Вера"))
        assertTrue(PersonIdentity.mentions("взять у веры сканы паспорта", "Вера"))
        assertFalse(PersonIdentity.mentions("верну книгу завтра", "Вера"))
        assertFalse(PersonIdentity.mentions("надо проверить счёт", "Вера"))
        assertFalse(PersonIdentity.mentions("я не уверен, что понял", "Вера"))
    }

    @Test
    fun `короткое имя не ищется вовсе`() {
        // «Ян» совпадёт со слишком многим; лучше не найти, чем найти чужое.
        assertFalse(PersonIdentity.mentions("январь выдался тёплым", "Ян"))
    }
}
