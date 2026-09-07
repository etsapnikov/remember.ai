package ai.prinim.prinyal.asr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Распаковка весов GigaAM из assets при первом запуске.
 *
 * onnxruntime открывает модель по пути в файловой системе, а asset — не файл:
 * внутри APK он лежит куском общего архива. Поэтому один раз копируем в
 * `files/models`. Плата — двойное место на диске (копия в APK плюс распакованная),
 * осознанная цена за APK, который ставится одним файлом без USB.
 */
object ModelStore {

    private const val TAG = "PrinyalAsr"
    private const val ASSET_DIR = "models"
    private val FILES = listOf(
        GigaAmOnDevice.ENCODER,
        GigaAmOnDevice.DECODER,
        GigaAmOnDevice.JOINT,
        GigaAmOnDevice.VOCAB,
    )

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    /**
     * Копирует веса, если их ещё нет. Идемпотентно: повторный вызов ничего не делает.
     *
     * @return true, если после вызова веса на месте
     */
    suspend fun install(context: Context): Boolean = withContext(Dispatchers.IO) {
        val target = dir(context)

        // Сверяем размеры на каждом запуске, а не выходим по «файлы на месте».
        //
        // Смена модели (v2 → v3 в 1.3) меняет и веса, и словарь, но имена файлов
        // те же. Ранний выход по наличию оставил бы на телефоне старый энкодер
        // рядом с новым словарём — распознавание молча поехало бы, а выглядело
        // как рабочее. Проверка стоит четырёх обращений к заголовку архива.
        val assets = context.assets
        try {
            FILES.forEach { name ->
                val file = File(target, name)
                val expected = assets.openFd("$ASSET_DIR/$name").use { it.length }
                if (file.length() == expected) return@forEach

                // Пишем во временный файл и переименовываем: прерванная на середине
                // распаковка не должна оставить огрызок, который выглядит как модель.
                val temp = File(target, "$name.part")
                assets.open("$ASSET_DIR/$name").use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, BUFFER) }
                }
                if (!temp.renameTo(file)) {
                    temp.delete()
                    error("не удалось переименовать $temp")
                }
                Log.i(TAG, "распакован $name: ${file.length() / 1_000_000} МБ")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "распаковка весов не удалась: ${e.message}")
            false
        }
    }

    private const val BUFFER = 1 shl 20
}
