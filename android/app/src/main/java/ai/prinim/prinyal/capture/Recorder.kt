package ai.prinim.prinyal.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.time.Instant

/**
 * Запись голоса (PRD §F-1).
 *
 * Стартует до отрисовки экрана: бюджет «тап по виджету → фактическое начало записи»
 * — 700 мс, и рисование в него не помещается.
 *
 * Авто-стоп: 2 с тишины после ≥ 3 с речи, потолок 90 с. Порог тишины настраиваемый —
 * на шумной улице фиксированный режет речь (PRD §11, п. 1).
 */
class Recorder(private val context: Context) {

    data class Result(
        val file: File,
        val durationMs: Long,
        val startedAt: Instant,
        val amplitudePeak: Int,
    )

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAtElapsed: Long = 0
    private var startedAt: Instant = Instant.EPOCH
    private var peak: Int = 0

    val isRecording: Boolean get() = recorder != null

    val elapsedMs: Long
        get() = if (isRecording) SystemClock.elapsedRealtime() - startedAtElapsed else 0

    fun start(): Boolean {
        if (isRecording) return false

        val target = File(audioDir(context), "${System.currentTimeMillis()}.m4a")
        val instance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            instance.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 16 kHz mono — ровно то, что ест GigaAM; больше писать незачем,
                // меньше — терять качество распознавания.
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setAudioEncodingBitRate(48_000)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
            recorder = instance
            file = target
            startedAtElapsed = SystemClock.elapsedRealtime()
            startedAt = Instant.now()
            peak = 0
            true
        } catch (e: Exception) {
            runCatching { instance.release() }
            target.delete()
            false
        }
    }

    /** Текущая громкость 0..32767. Кормит и пульс, и детектор тишины. */
    fun amplitude(): Int {
        val value = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        if (value > peak) peak = value
        return value
    }

    fun stop(): Result? {
        val instance = recorder ?: return null
        val target = file ?: return null
        val duration = elapsedMs

        recorder = null
        file = null

        return try {
            instance.stop()
            instance.release()
            Result(target, duration, startedAt, peak)
        } catch (e: RuntimeException) {
            // MediaRecorder бросает stop() на слишком коротком клипе и оставляет
            // битый файл — забирать нечего.
            runCatching { instance.release() }
            target.delete()
            null
        }
    }

    fun cancel() {
        val instance = recorder ?: return
        val target = file
        recorder = null
        file = null
        runCatching { instance.stop() }
        runCatching { instance.release() }
        target?.delete()
    }

    companion object {
        const val MAX_DURATION_MS = 90_000
        /** Ниже полутора секунд — случайное нажатие, такое не отправляется (§6). */
        const val MIN_DURATION_MS = 1_500L
        /** Пока речи меньше — тишину не считаем: человек ещё собирается с мыслями. */
        const val SPEECH_BEFORE_AUTOSTOP_MS = 3_000L

        fun audioDir(context: Context): File =
            File(context.filesDir, "audio").apply { mkdirs() }
    }
}
