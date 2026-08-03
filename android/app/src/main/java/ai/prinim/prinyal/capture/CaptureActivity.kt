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

    private lateinit var recorder: Recorder
    private var watchdog: Job? = null
    private var noteId: String = ""
    private var source: CaptureSource = CaptureSource.ICON
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
        noteId = UUID.randomUUID().toString()

        // Запись — раньше setContent: приёмка F-1 меряет момент старта записи,
        // а не момент появления пикселей.
        if (hasMic()) beginRecording() else state.needsPermission = true

        setContent {
            PrinyalTheme {
                CaptureScreen(
                    state = state,
                    onStop = ::finishRecording,
                    onCancel = ::cancelRecording,
                    onGrant = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
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
        if (recorder.isRecording) finishRecording()
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun beginRecording() {
        if (!recorder.start()) {
            state.failed = true
            return
        }
        state.needsPermission = false
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
     * Пульс, таймер и авто-стоп в одном цикле: 2 с тишины после ≥ 3 с речи, потолок 90 с.
     */
    private fun startWatchdog() {
        val threshold = PrinyalApp.of(this).settings
        watchdog = lifecycleScope.launch {
            val silenceThreshold = threshold.silenceThresholdNow()
            var speechMs = 0L
            var silenceMs = 0L

            while (recorder.isRecording) {
                delay(TICK_MS)
                val amplitude = recorder.amplitude()
                val elapsed = recorder.elapsedMs

                state.elapsedMs = elapsed
                state.level = (amplitude / 12_000f).coerceIn(0f, 1f)

                if (amplitude >= silenceThreshold) {
                    speechMs += TICK_MS
                    silenceMs = 0
                } else if (speechMs >= Recorder.SPEECH_BEFORE_AUTOSTOP_MS) {
                    silenceMs += TICK_MS
                }

                if (silenceMs >= Recorder.SILENCE_TO_STOP_MS) {
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
        state.receipt = true
        Haptics.receipt(this)

        val app = PrinyalApp.of(this)
        lifecycleScope.launch {
            app.analytics.log(Analytics.RECEIPT_SHOWN, mapOf("note" to noteId))
            app.repository.createNote(
                id = noteId,
                audio = result.file,
                durationMs = result.durationMs,
                source = source,
                createdAt = result.startedAt,
            )
            // Отправка — отдельной задачей: квитанция уже показана, и сеть её не держит.
            UploadWorker.enqueue(this@CaptureActivity, noteId)
        }

        lifecycleScope.launch {
            delay(RECEIPT_MS)
            finishAndRemoveTask()
        }
    }

    /** Свайп вниз — отмена. Другого способа передумать не нужно. */
    private fun cancelRecording() {
        if (state.receipt) return
        watchdog?.cancel()
        recorder.cancel()
        state.recording = false
        Haptics.cancel(this)

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
        private const val TAG = "PrinyalCapture"
        private const val TICK_MS = 100L
        /** Квитанция висит 0.6 с и закрывается сама. */
        private const val RECEIPT_MS = 600L
        private const val SHORT_TOAST_MS = 1_200L
    }
}
