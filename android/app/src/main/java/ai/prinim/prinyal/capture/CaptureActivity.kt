package ai.prinim.prinyal.capture

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.ui.theme.Haptics
import ai.prinim.prinyal.ui.theme.PrinyalTheme
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Экран захвата (PRD §F-1, §F-2).
 *
 * Между желанием и записью нет ни одного экрана и ни одного выбора: разрешение взято
 * заранее, MediaRecorder стартует в [onCreate] до отрисовки. Всё, что рисуется потом,
 * — это обратная связь, а не интерфейс принятия решений.
 */
class CaptureActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(ai.prinim.prinyal.ui.theme.LocaleForce.wrap(newBase))
    }

    private lateinit var recorder: Recorder
    private var watchdog: Job? = null
    /**
     * Таймер, который закрывает экран после квитанции.
     *
     * Держим его ссылкой, потому что дописывание приходит в живой экран: если
     * старый таймер не отменить, он через 0,6 с уносит task вместе с только что
     * начатой записью — экран «не открывается», хотя открылся (Р-15.1).
     */
    private var receiptJob: Job? = null
    private var noteId: String = ""
    private var source: CaptureSource = CaptureSource.ICON
    /** id заметки, к которой дописываем; null — обычная запись. */
    private var appendTo: String? = null
    private var launchedAt: Long = 0

    private val state = CaptureState()

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording() else state.needsPermission = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        launchedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)

        recorder = Recorder(this)
        source = CaptureSource.of(intent.getStringExtra(EXTRA_SOURCE))
        applyAppendTarget(intent)

        // Запись — раньше setContent: приёмка F-1 меряет момент старта записи,
        // а не момент появления пикселей.
        if (hasMic()) beginRecording() else state.needsPermission = true

        lifecycleScope.launch {
            val settings = PrinyalApp.of(this@CaptureActivity).settings
            state.showCancelHint = settings.hintVisible(HINT_CANCEL)
            state.showUpHint = settings.hintVisible(HINT_UP)
        }

        setContent {
            PrinyalTheme {
                val hasNotes by PrinyalApp.of(this).db.notes().feed()
                    .collectAsState(initial = emptyList())

                CaptureHost(
                    state = state,
                    hasNotes = hasNotes.isNotEmpty(),
                    onStop = ::finishRecording,
                    onCancel = ::cancelRecording,
                    onGrant = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                    onFeedOpened = {
                        stopForFeed()
                        lifecycleScope.launch {
                            PrinyalApp.of(this@CaptureActivity).settings.hintUsed(HINT_UP)
                        }
                    },
                    onStart = ::beginRecording,
                )
            }
        }
    }

    /**
     * Повторный тап по виджету во время записи останавливает её, а не плодит вторую
     * (приёмка F-1) — activity в singleTask, поэтому это приходит сюда.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Обязательно: без setIntent активити продолжает читать extras того
        // интента, с которым была создана. Из-за этого «Дописать» и не
        // работало — цель дописывания просто не доезжала (Р-15.1).
        setIntent(intent)

        val target = intent.getStringExtra(EXTRA_APPEND_TO)
        if (target != null) {
            // Дописывание приходит из карточки и обязано открыть запись из
            // любого состояния экрана: и когда он стоял в покое, и когда висела
            // квитанция, и когда шла чужая запись. Молча проигнорировать —
            // худший исход: человек нажал и ничего не случилось.
            if (recorder.isRecording) finishRecording()
            applyAppendTarget(intent)
            if (hasMic()) beginRecording() else state.needsPermission = true
            return
        }

        if (recorder.isRecording) {
            finishRecording()
        } else if (state.idle && hasMic()) {
            // Пришли с иконки/виджета, а экран стоял в idle — capture-first.
            source = CaptureSource.of(intent.getStringExtra(EXTRA_SOURCE))
            beginRecording()
        }
    }

    /** О ком запись, если пришли из карточки человека (Д-27). */
    private var about: String? = null

    /** Цель дописывания и подпись для плашки контекста. */
    private fun applyAppendTarget(intent: android.content.Intent) {
        appendTo = intent.getStringExtra(EXTRA_APPEND_TO)
        about = intent.getStringExtra(EXTRA_ABOUT)
        val id = appendTo
        if (id == null) {
            // «Рассказать про Веру» (Д-27): плашка та же, что у дописывания, —
            // человек видит, о ком говорит, и не гадает, куда попадёт запись.
            state.appendHint = about
            return
        }
        lifecycleScope.launch {
            val note = PrinyalApp.of(this@CaptureActivity).db.notes().byId(id)
            val words = note?.transcript?.takeIf { it.isNotBlank() }
                ?.split(Regex("\\s+"))?.take(4)?.joinToString(" ")
            state.appendHint = words ?: ""
        }
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun beginRecording() {
        // Отменяем закрытие по квитанции: новая запись отменяет прошлое
        // «до свидания». Без этого экран, открытый для дописывания, закрывался
        // сам через 0,6 с — таймером предыдущей записи.
        receiptJob?.cancel()
        receiptJob = null

        if (!recorder.start()) {
            state.failed = true
            return
        }
        noteId = UUID.randomUUID().toString()
        state.needsPermission = false
        // Квитанция прошлой записи гасится явно: пока флаг стоит, экран рисует
        // «Запомнил.» поверх идущей записи.
        state.receipt = false
        state.tooShort = false
        state.failed = false
        state.idle = false
        state.elapsedMs = 0
        state.silenceLeftMs = 0
        state.recording = true

        val app = PrinyalApp.of(this)
        lifecycleScope.launch {
            app.analytics.log(
                Analytics.CAPTURE_START,
                mapOf(
                    "note" to noteId,
                    "source" to source.wire,
                    // Бюджет F-1: от запуска до фактического старта записи ≤ 700 мс.
                    "to_record_ms" to (SystemClock.elapsedRealtime() - launchedAt),
                ),
            )
        }
        Log.i(TAG, "старт записи через ${SystemClock.elapsedRealtime() - launchedAt} мс")

        startWatchdog()
    }

    /**
     * Таймер, амплитуда и авто-стоп в одном цикле: тишина после ≥ 3 с речи,
     * потолок 90 с.
     *
     * Окно тишины не фиксировано, а растёт по мере того, как человек говорит —
     * [SilenceWindow]. Отсчёт до авто-стопа виден человеку (R1.2 §13): первые
     * [SilenceWindow.GRACE_MS] тишины копятся молча, это пауза между словами, —
     * а дальше под клавишей идёт обратный отсчёт. Слово громче порога сбрасывает
     * его; последние [SilenceWindow.LOCK_MS] необратимы: решение уже принято,
     * дёргать индикатор туда-сюда нечестно.
     */
    private fun startWatchdog() {
        val settings = PrinyalApp.of(this).settings
        watchdog = lifecycleScope.launch {
            val silenceThreshold = settings.silenceThresholdNow()
            val patience = settings.silencePatienceNow()
            var speechMs = 0L
            var silenceMs = 0L
            var wasSpeech = true

            while (recorder.isRecording) {
                delay(TICK_MS)
                val amplitude = recorder.amplitude()
                val elapsed = recorder.elapsedMs

                state.elapsedMs = elapsed
                state.level = Loudness.level(amplitude)

                // Окно считается от наговоренного: минута тишины в начале не
                // должна давать право на длинную паузу.
                val waitMs = SilenceWindow.waitMs(speechMs, patience)
                val locked = silenceMs >= waitMs - SilenceWindow.LOCK_MS
                wasSpeech = SilenceWindow.isSpeech(amplitude, silenceThreshold, wasSpeech)

                if (wasSpeech && !locked) {
                    speechMs += TICK_MS
                    silenceMs = 0
                } else if (speechMs >= Recorder.SPEECH_BEFORE_AUTOSTOP_MS) {
                    silenceMs += TICK_MS
                }

                state.silenceLeftMs = if (silenceMs >= SilenceWindow.GRACE_MS) {
                    (waitMs - silenceMs).coerceAtLeast(0)
                } else {
                    0
                }

                if (silenceMs >= waitMs) {
                    finishRecording()
                    return@launch
                }
                if (elapsed >= Recorder.MAX_DURATION_MS) {
                    finishRecording()
                    return@launch
                }
            }
        }
    }

    /**
     * Стоп. Квитанция показывается немедленно и не ждёт ни сети, ни распознавания —
     * она подтверждает захват, а не разбор (F-2).
     */
    private fun finishRecording() {
        if (state.receipt) return
        watchdog?.cancel()
        val result = recorder.stop()

        if (result == null || result.durationMs < Recorder.MIN_DURATION_MS) {
            // Случайное нажатие: тихо удаляем, ничего не обещаем (§6).
            result?.file?.delete()
            state.recording = false
            state.tooShort = true
            lifecycleScope.launch {
                PrinyalApp.of(this@CaptureActivity).analytics.log(
                    Analytics.CAPTURE_CANCEL,
                    mapOf("note" to noteId, "reason" to "too_short", "source" to source.wire),
                )
                delay(SHORT_TOAST_MS)
                finishAndRemoveTask()
            }
            return
        }

        state.recording = false
        state.silenceLeftMs = 0
        state.receipt = true
        state.appendHint = null
        Haptics.receipt(this)

        val app = PrinyalApp.of(this)
        val appendTo = this.appendTo
        lifecycleScope.launch {
            app.analytics.log(Analytics.RECEIPT_SHOWN, mapOf("note" to (appendTo ?: noteId)))
            // Дописывание — не новая запись: сегмент цепляется к существующей,
            // и разбор увидит оба куска речи как один текст.
            if (appendTo != null) {
                app.repository.appendSegment(appendTo, result.file, result.startedAt)
                UploadWorker.enqueue(this@CaptureActivity, appendTo)
                return@launch
            }
            app.repository.createNote(
                id = noteId,
                audio = result.file,
                durationMs = result.durationMs,
                source = source,
                createdAt = result.startedAt,
                peakAmplitude = result.amplitudePeak,
            )
            // Отправка — отдельной задачей: квитанция уже показана, и сеть её не держит.
            UploadWorker.enqueue(this@CaptureActivity, noteId)
        }

        receiptJob = lifecycleScope.launch {
            delay(RECEIPT_MS)
            // Дописывание возвращает туда, откуда пришли, — в тело заметки.
            //
            // Раньше экран просто убирал задачу, и человек оказывался на
            // рабочем столе: после ответа на вопрос «Покрутить идею» петля
            // рвалась ровно там, где должна была продолжиться следующим
            // вопросом. Новая запись по-прежнему уходит в никуда — она ни
            // откуда и не приходила.
            if (appendTo != null) {
                startActivity(
                    android.content.Intent(
                        this@CaptureActivity,
                        ai.prinim.prinyal.ui.MainActivity::class.java,
                    ).apply {
                        putExtra(ai.prinim.prinyal.ui.MainActivity.EXTRA_NOTE_ID, appendTo)
                        // Продолжаем разговор: экран заметки сам снова спросит.
                        putExtra(
                            ai.prinim.prinyal.ui.MainActivity.EXTRA_KEEP_ASKING,
                            intent.getBooleanExtra(EXTRA_ASKING, false),
                        )
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            finishAndRemoveTask()
        }
    }

    /**
     * Свайп вверх завершает запись (решение владельца 07.08, отмена §7 спеки R1.1:
     * запись при открытой ленте больше не живёт — она зомбировалась на потолке 90 с).
     * Сказанное сохраняется без квитанции-экрана, но с фирменным вибро; экран записи
     * остаётся в idle — новая запись по нажатию клавиши.
     */
    private fun stopForFeed() {
        if (!recorder.isRecording) {
            state.idle = true
            return
        }
        watchdog?.cancel()
        val result = recorder.stop()
        state.recording = false
        state.elapsedMs = 0
        state.silenceLeftMs = 0
        state.idle = true

        if (result == null || result.durationMs < Recorder.MIN_DURATION_MS) {
            result?.file?.delete()
            lifecycleScope.launch {
                PrinyalApp.of(this@CaptureActivity).analytics.log(
                    Analytics.CAPTURE_CANCEL,
                    mapOf("note" to noteId, "reason" to "feed_short", "source" to source.wire),
                )
            }
            return
        }

        Haptics.receipt(this)
        val app = PrinyalApp.of(this)
        lifecycleScope.launch {
            app.repository.createNote(
                id = noteId,
                audio = result.file,
                durationMs = result.durationMs,
                source = source,
                createdAt = result.startedAt,
            )
            UploadWorker.enqueue(this@CaptureActivity, noteId)
        }
    }

    /**
     * Свайп вверх — лента и настройки.
     *
     * Начатую запись отбрасываем: человек пришёл разбирать накопленное, а не
     * говорить, — сохранять нечего. Иначе каждый заход в ленту оставлял бы после
     * себя мусорную запись «не расслышал».
     */
    private fun openFeed() {
        if (state.receipt) return
        if (recorder.isRecording) {
            watchdog?.cancel()
            recorder.cancel()
            state.recording = false
            // Иначе в аналитике остаётся capture_start без пары и выглядит как
            // брошенная запись, хотя человек просто ушёл в ленту.
            lifecycleScope.launch {
                PrinyalApp.of(this@CaptureActivity).analytics.log(
                    Analytics.CAPTURE_CANCEL,
                    mapOf("note" to noteId, "reason" to "feed", "source" to source.wire),
                )
            }
        }
        // NEW_TASK обязателен: без него лента попадает в задачу экрана записи,
        // а он через мгновение делает finishAndRemoveTask() и уносит ленту с собой.
        startActivity(
            android.content.Intent(this, ai.prinim.prinyal.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /** Свайп вниз — отмена. Другого способа передумать не нужно. */
    private fun cancelRecording() {
        if (state.receipt) return
        watchdog?.cancel()
        recorder.cancel()
        state.recording = false
        Haptics.cancel(this)
        lifecycleScope.launch { PrinyalApp.of(this@CaptureActivity).settings.hintUsed(HINT_CANCEL) }

        lifecycleScope.launch {
            PrinyalApp.of(this@CaptureActivity).analytics.log(
                Analytics.CAPTURE_CANCEL,
                mapOf("note" to noteId, "reason" to "swipe", "source" to source.wire),
            )
            finishAndRemoveTask()
        }
    }

    override fun onPause() {
        super.onPause()
        // Уход из приложения во время записи — это не отмена: человека отвлекли.
        // Дописываем и отправляем, чтобы сказанное не пропало.
        if (recorder.isRecording && !isChangingConfigurations) finishRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdog?.cancel()
        if (recorder.isRecording) recorder.cancel()
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        /** id заметки, к которой дописываем (Р-14.3). */
        const val EXTRA_APPEND_TO = "append_to"

        /**
         * Про кого запись (Д-27). Плашка «про Веру» на экране захвата — тот же
         * механизм, что у дописывания: продукт не заводит форму для факта, он
         * слушает тем же жестом, каким слушает всё остальное.
         */
        const val EXTRA_ABOUT = "about"

        /** Ответ на вопрос «Покрутить идею»: после квитанции разговор продолжается. */
        const val EXTRA_ASKING = "asking"
        private const val TAG = "PrinyalCapture"
        private const val TICK_MS = 100L
        private const val HINT_CANCEL = "cancel"
        private const val HINT_UP = "up"
        /** Квитанция висит 0.6 с и закрывается сама. */
        private const val RECEIPT_MS = 600L
        private const val SHORT_TOAST_MS = 1_200L
    }
}
