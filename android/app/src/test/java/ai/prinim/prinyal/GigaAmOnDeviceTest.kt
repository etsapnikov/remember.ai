package ai.prinim.prinyal

import ai.prinim.prinyal.asr.GigaAmOnDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Распознавание на устройстве, проверенное на настоящей русской речи.
 *
 * Тест гоняет тот же код и те же веса, что поедут на телефон, — просто в JVM
 * (десктопный артефакт onnxruntime имеет тот же API). Без него первая проверка
 * распознавания случилась бы на телефоне, где ошибка выглядит как «оно просто
 * плохо слышит», а не как конкретный баг в декоде или признаках.
 *
 * Веса лежат вне репозитория (338 МБ). Нет весов — тест пропускается, а не врёт
 * зелёным.
 */
class GigaAmOnDeviceTest {

    private val models = File(
        System.getenv("PRINYAL_MODELS")
            ?: "/Users/etsapnikov/Documents/prinyal/backend/models/android"
    )

    /** Эталон: то же, что выдают torch и Python-ONNX на этом клипе. */
    private val expected = "купить капле соню к лору записать и мужу сказать что суббота занята"

    private fun speech(): FloatArray {
        val stream = javaClass.classLoader!!.getResourceAsStream("speech_ru.wav")
            ?: error("нет speech_ru.wav")
        val bytes = stream.readBytes()
        // Заголовок WAV фиксированный: PCM 16 кГц моно 16 бит, данные с 44-го байта.
        val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray((bytes.size - 44) / 2)
        for (i in samples.indices) samples[i] = buffer.short / 32768f
        return samples
    }

    @Test
    fun `распознаёт русскую речь так же, как эталонный конвейер`() {
        assumeTrue(
            "нет весов в ${models.absolutePath} — тест пропущен",
            GigaAmOnDevice.modelsPresent(models),
        )

        GigaAmOnDevice(models).use { asr ->
            val started = System.currentTimeMillis()
            val text = asr.transcribe(speech())
            val took = System.currentTimeMillis() - started
            println("ASR: ${took}ms → «$text»")

            assertEquals(expected, text)
        }
    }

    @Test
    fun `тишина не выдумывает слов`() {
        assumeTrue(GigaAmOnDevice.modelsPresent(models))
        GigaAmOnDevice(models).use { asr ->
            val text = asr.transcribe(FloatArray(16_000))
            assertTrue("на тишине получили «$text»", text.isBlank())
        }
    }

    @Test
    fun `словарь совпадает с конфигом модели`() {
        // 33 метки: пробел и 32 буквы. blank идёт следом — сдвиг здесь означает,
        // что декод будет молча выдавать не те символы.
        assertEquals(33, GigaAmOnDevice.LABELS.size)
        assertEquals(GigaAmOnDevice.LABELS.size, GigaAmOnDevice.BLANK)
        assertEquals(" ", GigaAmOnDevice.LABELS.first())
        assertEquals("я", GigaAmOnDevice.LABELS.last())
    }
}
