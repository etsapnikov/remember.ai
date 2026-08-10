package ai.prinim.prinyal

import ai.prinim.prinyal.capture.Loudness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нормировка громкости для кольца записи (R1.2 §13).
 *
 * Числовой контракт, который ломается молча: при линейной нормировке кольцо
 * дышало на два пикселя и выглядело сломанным, хотя данные шли. Пины ниже держат
 * рабочий диапазон, а не конкретную формулу.
 */
class LoudnessTest {

    @Test
    fun `тишина и мусорные значения дают ноль`() {
        assertEquals(0f, Loudness.level(0), 0.0001f)
        assertEquals(0f, Loudness.level(-5), 0.0001f)
        // Тихая комната: −44 дБFS, ниже рабочего диапазона.
        assertEquals(0f, Loudness.level(200), 0.0001f)
    }

    @Test
    fun `максимум шкалы даёт полный ход`() {
        assertEquals(1f, Loudness.level(32_767), 0.0001f)
        assertEquals(1f, Loudness.level(20_000), 0.0001f)
    }

    @Test
    fun `реальная речь занимает верхнюю половину хода`() {
        // Пик через динамики на телефоне владельца — с него и начался разбор.
        val throughSpeakers = Loudness.level(3_480)
        assertTrue("через динамики получилось $throughSpeakers", throughSpeakers in 0.5f..0.65f)

        // Голос прямо в телефон — кольцо почти на полном ходу.
        val closeSpeech = Loudness.level(12_000)
        assertTrue("вблизи получилось $closeSpeech", closeSpeech > 0.85f)
    }

    @Test
    fun `уровень растёт монотонно`() {
        val samples = listOf(300, 900, 1_500, 3_500, 8_000, 16_000)
        val levels = samples.map(Loudness::level)
        assertEquals(levels.sorted(), levels)
    }

    @Test
    fun `порог тишины авто-стопа ещё не считается речью для кольца`() {
        // Дефолт «обычно» — 900: кольцо на этом уровне едва заметно, и это верно,
        // иначе оно дрожало бы от фонового шума.
        assertTrue(Loudness.level(900) < 0.25f)
    }
}
