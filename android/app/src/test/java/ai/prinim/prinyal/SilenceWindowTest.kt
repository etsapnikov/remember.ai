package ai.prinim.prinyal

import ai.prinim.prinyal.capture.SilenceWindow
import ai.prinim.prinyal.capture.SilenceWindow.Patience
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Окно тишины. Проверяем не столько числа, сколько обещания: короткая просьба
 * закрывается быстро, длинная мысль получает право на паузу, а окно не скачет.
 */
class SilenceWindowTest {

    @Test
    fun `короткая просьба закрывается быстро`() {
        // «купить молока» — три секунды речи.
        assertEquals(2_500L, SilenceWindow.waitMs(3_000))
    }

    @Test
    fun `длинная мысль получает право на долгую паузу`() {
        val short = SilenceWindow.waitMs(5_000)
        val long = SilenceWindow.waitMs(60_000)
        assertTrue("минута речи должна давать паузу длиннее трёх секунд", long >= 6_000)
        assertTrue(long > short * 2)
    }

    @Test
    fun `окно растёт монотонно, без скачков назад`() {
        var previous = 0L
        var spoken = 0L
        while (spoken <= 90_000) {
            val current = SilenceWindow.waitMs(spoken)
            assertTrue("окно сжалось на $spoken мс: $previous → $current", current >= previous)
            previous = current
            spoken += 500
        }
    }

    @Test
    fun `терпение растягивает и сжимает окно`() {
        val normal = SilenceWindow.waitMs(30_000, Patience.NORMAL)
        assertTrue(SilenceWindow.waitMs(30_000, Patience.SHORT) < normal)
        assertTrue(SilenceWindow.waitMs(30_000, Patience.LONG) > normal)
    }

    @Test
    fun `даже самое нетерпеливое окно длиннее прежних двух секунд`() {
        // Прежняя константа рвала мысль на паузе; ни одна настройка не должна
        // возвращать нас к ней на длинной речи.
        assertTrue(SilenceWindow.waitMs(30_000, Patience.SHORT) > 2_000)
    }

    @Test
    fun `громкая речь и явная тишина читаются однозначно`() {
        assertTrue(SilenceWindow.isSpeech(amplitude = 5_000, threshold = 900, wasSpeech = false))
        assertFalse(SilenceWindow.isSpeech(amplitude = 100, threshold = 900, wasSpeech = true))
    }

    @Test
    fun `в серой зоне состояние не дребезжит`() {
        // Дыхание ровно на пороге раньше то сбрасывало отсчёт, то нет.
        val grey = 1_000 // между 900 и 900*1.3
        assertTrue(SilenceWindow.isSpeech(grey, threshold = 900, wasSpeech = true))
        assertFalse(SilenceWindow.isSpeech(grey, threshold = 900, wasSpeech = false))
    }

    @Test
    fun `хвост отсчёта короче самого окна`() {
        // Иначе отсчёт стал бы необратимым с первой же миллисекунды.
        assertTrue(SilenceWindow.LOCK_MS < SilenceWindow.waitMs(0, Patience.SHORT))
        assertTrue(SilenceWindow.GRACE_MS < SilenceWindow.waitMs(0, Patience.SHORT))
    }
}
