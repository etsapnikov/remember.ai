package ai.prinim.prinyal

import ai.prinim.prinyal.asr.GigaAmOnDevice
import ai.prinim.prinyal.asr.ModelStore
import ai.prinim.prinyal.llm.DeepSeekClient
import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CrashLog
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
        NoteRepository(db, settings, analytics, returnScheduler, llm = { llm })
    }

    @Volatile
    private var asrEngine: GigaAmOnDevice? = null

    /**
     * Распознавание на устройстве. `null`, пока весов нет: приложение при этом
     * работает — записи сохраняются и ждут, — но транскрипта не будет.
     *
     * Не `by lazy`: ленивое поле, вычисленное до распаковки весов, навсегда
     * запомнило бы null, и распознавание не завелось бы до перезапуска процесса.
     */
    /** Подмена распознавания для тестов — см. [ai.prinim.prinyal.asr.SpeechToText]. */
    @Volatile
    private var asrOverride: ai.prinim.prinyal.asr.SpeechToText? = null

    @androidx.annotation.VisibleForTesting
    fun useAsr(engine: ai.prinim.prinyal.asr.SpeechToText) {
        asrOverride = engine
    }

    fun asr(): ai.prinim.prinyal.asr.SpeechToText? {
        asrOverride?.let { return it }
        asrEngine?.let { return it }
        val dir = ModelStore.dir(this)
        if (!GigaAmOnDevice.modelsPresent(dir)) return null
        return synchronized(this) {
            asrEngine ?: GigaAmOnDevice(dir).also { asrEngine = it }
        }
    }

    /**
     * Отпустить движок распознавания (Р-24.1).
     *
     * Он держал в памяти 326 МБ весов **всё время работы приложения** — от
     * первой записи до убийства процесса. Пока человек только записывает, это
     * незаметно; стоит начать листать разделы, ленту и «Дни», как система
     * выбирает, кого убить, и выбирает нас. Для человека это «приложение
     * вылетело при переключении разделов»: ни трейса, ни записи в журнале
     * падений — процесс убивают снаружи, без исключения.
     *
     * Зовётся после того, как воркер закончил пачку: следующая запись поднимет
     * движок заново за секунду-другую, и это дешевле, чем вылет.
     */
    fun releaseAsr() {
        synchronized(this) {
            runCatching { asrEngine?.close() }
            asrEngine = null
        }
    }

    /**
     * Разбор комка. Ключ вшит в сборку — версия работает без сервера
     * (отступление от PRD §2 п.3, решение владельца). Настройки могут его
     * переопределить, если ключ придётся сменить без пересборки.
     */
    /**
     * Подмена разбора для тестов.
     *
     * Без этого шва главный путь продукта непроверяем: воркер берёт разбор
     * отсюда, а не получает его аргументом, и любой тест на путь «наговорил →
     * появилось в ленте» уходил бы в сеть к живой модели. Тест на живой модели
     * проверяет её настроение, стоит денег и краснеет не по нашей вине.
     */
    @Volatile
    private var llmOverride: DeepSeekClient? = null

    @androidx.annotation.VisibleForTesting
    fun useLlm(client: DeepSeekClient) {
        llmOverride = client
    }

    val llm: DeepSeekClient get() = llmOverride ?: realLlm

    private val realLlm: DeepSeekClient by lazy {
        DeepSeekClient(
            apiKey = settings.deepSeekKey.ifBlank { BuildConfig.DEEPSEEK_KEY },
            // Расход токенов копится в той же аналитике, что и всё остальное:
            // отдельного хранилища ради одной цифры заводить нечего.
            usageListener = { usage ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    analytics.log(
                        Analytics.LLM_USAGE,
                        mapOf(
                            "kind" to usage.kind,
                            "cached_in" to usage.cachedIn,
                            "fresh_in" to usage.freshIn,
                            "out" to usage.out,
                        ),
                    )
                }
            },
        )
    }

    /** Журнал падений (Р-17.1): без записи разговор о крашах — обмен догадками. */
    val crashes: CrashLog by lazy { CrashLog(this) }

    override fun onCreate() {
        super.onCreate()
        // Первым делом: падение при старте — тоже падение, и оно самое частое.
        crashes.install()
        Notifications.ensureChannels(this)
        ai.prinim.prinyal.returns.DayAsk.ensureChannel(this)
        // Вечерний вопрос и итог недели: алармы одноразовые, пересбор на каждом
        // старте — та же страховка, что у возвратов.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            ai.prinim.prinyal.returns.DayAsk.schedule(this@PrinyalApp, settings.bedtimeNow())
        }
        // Страховка возвратов (Р-8): прошивка душит алармы, воркер догоняет.
        ai.prinim.prinyal.returns.ReturnCatchUpWorker.ensureScheduled(this)

        // Тумблер разбора уехал в «Для разработчика» — выключенный с прошлой
        // версии он оставил бы человека без разбора и без видимого способа
        // вернуть его (Р-15.5).
        CoroutineScope(Dispatchers.IO).launch { settings.healParsingToggle() }

        // Распаковку весов из onCreate убрали намеренно: 326 МБ — не работа для
        // старта приложения, а Robolectric на ней валил heap в каждом тесте.
        // Теперь веса ставятся перед первым распознаванием, в воркере.
    }

    companion object {
        fun of(context: Context): PrinyalApp =
            context.applicationContext as PrinyalApp
    }
}
