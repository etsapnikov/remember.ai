package ai.prinim.prinyal

import ai.prinim.prinyal.domain.InterviewPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Генерик-вопрос — брак, а не слабый результат: получив «а что дальше?»,
 * человек справедливо решит, что его не слушали, и режим больше не откроет.
 * Поэтому здесь проверяется в основном то, что отбрасывается.
 */
class InterviewPolicyTest {

    private val idea =
        "хочу сделать приложение, которое собирает контекст по проекту из моих " +
            "голосовых заметок и отдаёт его одним файлом перед встречей"

    @Test
    fun `конкретный вопрос по словам идеи проходит`() {
        assertTrue(
            InterviewPolicy.accepts("Что должно попадать в файл перед встречей, а что нет?", idea)
        )
    }

    @Test
    fun `генерик не проходит, даже вежливый`() {
        listOf(
            "А что дальше?",
            "Какой первый шаг ты видишь?",
            "С чего начать?",
            "Какие есть варианты?",
        ).forEach { assertFalse("прошёл генерик: $it", InterviewPolicy.accepts(it, idea)) }
    }

    @Test
    fun `вопрос не про эту идею не проходит`() {
        // Формально конкретный, но подошёл бы к любой другой записи — значит
        // модель не прочла идею.
        assertFalse(
            InterviewPolicy.accepts("Сколько времени ты готов тратить на это в неделю?", idea)
        )
    }

    @Test
    fun `повтор по смыслу отбрасывается`() {
        val asked = listOf("Что должно попадать в файл перед встречей?")
        assertFalse(
            "переспросил то же другими словами",
            InterviewPolicy.accepts("Что именно попадает в файл перед встречей?", idea, asked),
        )
        assertTrue(
            InterviewPolicy.accepts("Кому ты отдаёшь этот файл — себе или коллегам?", idea, asked)
        )
    }

    @Test
    fun `не вопрос и не по размеру не проходят`() {
        assertFalse(InterviewPolicy.accepts("Расскажи про файл перед встречей.", idea))
        assertFalse(InterviewPolicy.accepts("А?", idea))
        assertFalse(InterviewPolicy.accepts("Файл?" + " и".repeat(200), idea))
    }

    @Test
    fun `человек выходит из режима словами`() {
        listOf("хватит", "Стоп.", "всё", "достаточно").forEach {
            assertTrue("не понял «$it»", InterviewPolicy.isEnough(it))
        }
        assertFalse(InterviewPolicy.isEnough("хватит времени на это не будет"))
    }
}
