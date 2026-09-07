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
 * Веса лежат вне репозитория (310 МБ). Нет весов — тест пропускается, а не врёт
 * зелёным.
 */
class GigaAmOnDeviceTest {

    private val models = File(
        System.getenv("PRINYAL_MODELS")
            ?: "/Users/etsapnikov/Documents/prinyal/backend/models/gigaam-v3"
    )

    /**
     * Эталон: то же, что выдаёт Python-ONNX на этом клипе с теми же весами.
     *
     * До 1.3 здесь стояло «купить капле соню к лору записать и мужу сказать что
     * суббота занята» — без знаков, без заглавных и с «капле» вместо «капли».
     * Разница в эталоне и есть смысл перехода на v3.
     */
    private val expected =
        "Купить капли, Соню к Лору записать и мужу сказать, что суббота занята."

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
        assumeTrue(GigaAmOnDevice.modelsPresent(models))
        val vocab = GigaAmOnDevice.readVocab(File(models, GigaAmOnDevice.VOCAB))

        // 1025 кусков — `num_classes` из v3_e2e_rnnt.yaml. Сдвиг здесь означает,
        // что декод будет молча выдавать не те куски.
        assertEquals(1025, vocab.size)
        assertEquals(1024, vocab.indexOf(GigaAmOnDevice.BLANK_TOKEN))
    }

    @Test
    fun `в словаре есть латиница — ради неё и меняли модель`() {
        assumeTrue(GigaAmOnDevice.modelsPresent(models))
        val vocab = GigaAmOnDevice.readVocab(File(models, GigaAmOnDevice.VOCAB))

        // У прежней v2 алфавит был русский, и английское слово она записывала
        // кириллицей на слух. Пропадёт латиница из словаря — вернётся «джоп
        // дискрипшен», причём тихо: текст будет выглядеть распознанным.
        val latin = vocab.count { piece -> piece.any { it in 'a'..'z' || it in 'A'..'Z' } }
        assertTrue("латинских кусков $latin", latin > 50)

        // Пунктуация оттуда же: до v3 транскрипт приходил вовсе без знаков.
        assertTrue(vocab.contains(","))
        assertTrue(vocab.contains("."))
    }
}
