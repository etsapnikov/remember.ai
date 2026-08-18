package ai.prinim.prinyal

import ai.prinim.prinyal.data.LinkReason
import ai.prinim.prinyal.domain.LinkValidator
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Связь, которой нет, хуже отсутствующей: она утверждает, что две мысли про
 * одно, и человек ищет продолжение там, где его нет. Поэтому здесь проверяется
 * не «находит ли», а «что отбрасывает».
 *
 * Robolectric — ради настоящего `org.json`: на голой JVM это заглушка из
 * android.jar, молча отдающая пустоту, и тесты зеленели бы на пустом разборе.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkValidatorTest {

    private val candidates = setOf("n1", "n2", "n3", "n4")

    private fun links(json: String) =
        LinkValidator.validate(JSONArray(json), candidates, selfId = "me")

    @Test
    fun `обычный случай проходит целиком`() {
        val out = links("""[{"ref":"n1","reason":"continues","confidence":"high"}]""")
        assertEquals(1, out.size)
        assertEquals(LinkReason.CONTINUES, out[0].reason)
    }

    @Test
    fun `ссылка на чужую заметку не проходит`() {
        // Модель вправе промахнуться id, и тогда связь укажет на постороннюю
        // запись — молча и правдоподобно. Принимаем только тех, кого сами и
        // предложили.
        assertTrue(links("""[{"ref":"n9","reason":"continues","confidence":"high"}]""").isEmpty())
    }

    @Test
    fun `самоссылка отбрасывается`() {
        assertTrue(links("""[{"ref":"me","reason":"continues","confidence":"high"}]""").isEmpty())
    }

    @Test
    fun `дубль по заметке схлопывается в первый`() {
        // Две причины к одной записи означают, что модель не выбрала, а
        // перечислила.
        val out = links(
            """[{"ref":"n1","reason":"continues","confidence":"high"},
                {"ref":"n1","reason":"disputes","confidence":"high"}]"""
        )
        assertEquals(1, out.size)
        assertEquals(LinkReason.CONTINUES, out[0].reason)
    }

    @Test
    fun `неуверенная связь не показывается`() {
        // У связи нет мягкой формы: она либо есть на карточке, либо нет.
        assertTrue(links("""[{"ref":"n1","reason":"continues","confidence":"low"}]""").isEmpty())
        assertTrue(links("""[{"ref":"n1","reason":"continues"}]""").isEmpty())
    }

    @Test
    fun `выдуманная причина не проходит`() {
        assertTrue(links("""[{"ref":"n1","reason":"related","confidence":"high"}]""").isEmpty())
    }

    @Test
    fun `больше трёх связей не бывает`() {
        val out = links(
            (1..4).joinToString(",", "[", "]") {
                """{"ref":"n$it","reason":"continues","confidence":"high"}"""
            }
        )
        assertEquals(LinkValidator.MAX_LINKS, out.size)
    }

    @Test
    fun `отсутствие поля и мусор не роняют разбор`() {
        assertTrue(LinkValidator.validate(null, candidates, "me").isEmpty())
        assertTrue(links("""[null, 5, {"ref":""}]""").isEmpty())
    }
}
