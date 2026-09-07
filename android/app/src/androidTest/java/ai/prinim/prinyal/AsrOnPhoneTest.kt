package ai.prinim.prinyal

import ai.prinim.prinyal.asr.GigaAmOnDevice
import ai.prinim.prinyal.asr.ModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Распознавание на настоящем телефоне, а не в JVM.
 *
 * Единственная проверка, которую нельзя сделать на маке. Веса под v3 мы
 * переквантовали сами (`scripts/quantize_v3.py`), потому что вендорские
 * квантуют свёртки в `ConvInteger`, а onnxruntime отвечает «not implemented».
 * Наш вариант заведомо работает на десктопной 1.23 — но на телефоне другая
 * сборка (1.20) и другая архитектура (arm64), и «у меня на маке зелено» тут
 * ничего не доказывает.
 *
 * Тест гоняет тот же клип, что и JVM-тест, и ждёт тот же текст. Разойдётся —
 * значит рантайм на телефоне считает иначе, и это надо знать до того, как
 * владелец наговорит в него неделю заметок.
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 *
 * ВНИМАНИЕ: этот прогон **сносит приложение** вместе с данными — так устроен
 * connectedAndroidTest. На телефоне владельца там живой дневник, поэтому гнать
 * его туда можно только после `scripts/backup.sh` и с готовностью восстановить.
 * По-хорошему — только на эмуляторе:
 *
 *     ANDROID_SERIAL=emulator-5560 ./gradlew :app:connectedDebugAndroidTest
 */
class AsrOnPhoneTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val expected =
        "Купить капли, Соню к Лору записать и мужу сказать, что суббота занята."

    private fun speech(): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("speech_ru.wav").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray((bytes.size - 44) / 2)
        for (i in samples.indices) samples[i] = buffer.short / 32768f
        return samples
    }

    @Test
    fun распаковка_кладёт_на_телефон_веса_из_сборки() = runBlocking {
        assertTrue("распаковка не удалась", ModelStore.install(context))
        val dir = ModelStore.dir(context)
        assertTrue(GigaAmOnDevice.modelsPresent(dir))

        // Ровно тот случай, ради которого распаковка перестала верить в «файлы
        // на месте»: имена не изменились, а веса и словарь другие.
        val assets = context.assets
        listOf(GigaAmOnDevice.ENCODER, GigaAmOnDevice.VOCAB).forEach { name ->
            val inApk = assets.openFd("models/$name").use { it.length }
            assertEquals("$name разошёлся с APK", inApk, File(dir, name).length())
        }
    }

    @Test
    fun рантайм_телефона_считает_так_же_как_десктоп() = runBlocking {
        ModelStore.install(context)
        GigaAmOnDevice(ModelStore.dir(context)).use { asr ->
            val started = System.currentTimeMillis()
            val text = asr.transcribe(speech())
            println("ASR на телефоне: ${System.currentTimeMillis() - started} мс → «$text»")
            assertEquals(expected, text)
        }
    }

    @Test
    fun английские_слова_приходят_латиницей() = runBlocking {
        ModelStore.install(context)
        val vocab = GigaAmOnDevice.readVocab(File(ModelStore.dir(context), GigaAmOnDevice.VOCAB))
        val latin = vocab.count { piece -> piece.any { it in 'a'..'z' || it in 'A'..'Z' } }
        assertTrue("латинских кусков на телефоне $latin", latin > 50)
    }
}
