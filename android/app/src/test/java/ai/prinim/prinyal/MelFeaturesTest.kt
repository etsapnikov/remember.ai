package ai.prinim.prinyal

import ai.prinim.prinyal.asr.MelFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Признаки на устройстве обязаны совпадать с обучающим препроцессингом GigaAM.
 *
 * Ошибка здесь не падает, а тихо портит распознавание: модель выдаёт похожий на
 * речь, но неверный текст — и понять это по логам невозможно. Поэтому сверяемся с
 * золотым образцом, посчитанным torchaudio (`logmel_golden.bin`, генератор —
 * в истории backend'а).
 */
class MelFeaturesTest {

    /** Тот же сигнал, что задан формулой при генерации образца. */
    private fun signal(): FloatArray {
        val sr = MelFeatures.SAMPLE_RATE
        return FloatArray(sr) { n ->
            (0.5 * sin(2 * PI * 440 * n / sr) + 0.25 * sin(2 * PI * 1970 * n / sr)).toFloat()
        }
    }

    private fun golden(): Pair<Int, Array<FloatArray>> {
        val stream = javaClass.classLoader!!.getResourceAsStream("logmel_golden.bin")
            ?: error("нет logmel_golden.bin в test resources")
        val bytes = stream.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val mels = buffer.int
        val frames = buffer.int
        val data = Array(mels) { FloatArray(frames) }
        for (m in 0 until mels) {
            for (t in 0 until frames) data[m][t] = buffer.float
        }
        return frames to data
    }

    @Test
    fun `log-mel совпадает с torchaudio`() {
        val (frames, expected) = golden()
        val actual = MelFeatures.logMel(signal())

        assertEquals("число мел-полос", MelFeatures.N_MELS, actual.size)
        assertEquals("число кадров", frames, actual[0].size)

        // Порог «есть сигнал» считаем от пика, а не константой: полоса на 20 единиц
        // логарифма ниже пика — это мощность в 5·10⁻⁹ от него, численный ноль, где
        // расхождение задаёт округление float32 в самой torchaudio (мы считаем в
        // double и потому точнее). Сверяем строго там, где сигнал есть, и отдельно
        // проверяем, что тихие полосы остались тихими.
        val peak = expected.maxOf { row -> row.max() }
        val floor = peak - 20f
        var worstSignal = 0.0f
        var worstSignalAt = ""
        var worstQuiet = 0.0f

        for (m in expected.indices) {
            for (t in expected[m].indices) {
                val diff = abs(expected[m][t] - actual[m][t])
                if (expected[m][t] > floor) {
                    if (diff > worstSignal) {
                        worstSignal = diff
                        worstSignalAt = "мел $m, кадр $t: " +
                            "ждали ${expected[m][t]}, получили ${actual[m][t]}"
                    }
                } else if (diff > worstQuiet) {
                    worstQuiet = diff
                }
            }
        }

        assertTrue(
            "в полосах с сигналом расхождение $worstSignal — $worstSignalAt",
            worstSignal < 1e-3f,
        )
        assertTrue(
            "тихие полосы разъехались на $worstQuiet — это уже не округление",
            worstQuiet < 0.2f,
        )
    }

    @Test
    fun `тишина даёт пол логарифма, а не NaN`() {
        val actual = MelFeatures.logMel(FloatArray(MelFeatures.SAMPLE_RATE))
        actual.forEach { row ->
            row.forEach { value ->
                assertTrue("получен $value", value.isFinite())
                assertEquals(kotlin.math.ln(1e-9f), value, 1e-4f)
            }
        }
    }

    @Test
    fun `короткий сигнал не роняет извлечение`() {
        val actual = MelFeatures.logMel(FloatArray(1_000) { 0.1f })
        assertEquals(MelFeatures.N_MELS, actual.size)
        assertTrue(actual[0].isNotEmpty())
    }
}
