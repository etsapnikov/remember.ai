package ai.prinim.prinyal

import ai.prinim.prinyal.asr.GigaAmOnDevice
import ai.prinim.prinyal.asr.ModelStore
import ai.prinim.prinyal.llm.DeepSeekClient
import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.domain.NoteRepository
import ai.prinim.prinyal.net.IngestApi
import ai.prinim.prinyal.returns.Notifications
import ai.prinim.prinyal.returns.ReturnScheduler
import ai.prinim.prinyal.capture.UploadWorker
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Точка сборки. DI-фреймворка нет намеренно: приложение одноэкранное по сути, а
 * лишний слой — это ещё одна вещь, которая может сломаться в dogfood-версии.
 */
class PrinyalApp : Application() {

    val db: PrinyalDb by lazy { PrinyalDb.get(this) }
    val settings: Settings by lazy { Settings(this) }
    val analytics: Analytics by lazy { Analytics(this) }
    val api: IngestApi by lazy { IngestApi() }
    val returnScheduler: ReturnScheduler by lazy { ReturnScheduler(this) }

    val repository: NoteRepository by lazy {
        NoteRepository(db, settings, analytics, returnScheduler)
    }

    /**
     * Распознавание на устройстве. `null`, пока весов нет: приложение при этом
     * работает — записи сохраняются и ждут, — но транскрипта не будет.
     *
     * Веса лежат в `files/models` (~326 МБ), в APK их нет.
     */
    val asr: GigaAmOnDevice? by lazy {
        val dir = ModelStore.dir(this)
        if (GigaAmOnDevice.modelsPresent(dir)) GigaAmOnDevice(dir) else null
    }

    /**
     * Разбор комка. Ключ вшит в сборку — версия работает без сервера
     * (отступление от PRD §2 п.3, решение владельца). Настройки могут его
     * переопределить, если ключ придётся сменить без пересборки.
     */
    val llm: DeepSeekClient by lazy {
        DeepSeekClient(apiKey = settings.deepSeekKey.ifBlank { BuildConfig.DEEPSEEK_KEY })
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)

        // Веса едут в APK и распаковываются один раз, в фоне. Пока распаковка идёт,
        // запись работает: она и не должна ничего ждать (F-2) — транскрипт просто
        // появится следующим заходом очереди.
        CoroutineScope(Dispatchers.IO).launch {
            if (ModelStore.install(this@PrinyalApp)) {
                UploadWorker.kick(this@PrinyalApp)
            }
        }
    }

    companion object {
        fun of(context: Context): PrinyalApp =
            context.applicationContext as PrinyalApp
    }
}
