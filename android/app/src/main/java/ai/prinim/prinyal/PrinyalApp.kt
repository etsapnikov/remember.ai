package ai.prinim.prinyal

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.domain.NoteRepository
import ai.prinim.prinyal.net.IngestApi
import ai.prinim.prinyal.returns.Notifications
import ai.prinim.prinyal.returns.ReturnScheduler
import android.app.Application
import android.content.Context

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

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    companion object {
        fun of(context: Context): PrinyalApp =
            context.applicationContext as PrinyalApp
    }
}
