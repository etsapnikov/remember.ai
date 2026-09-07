package ai.prinim.prinyal.capture

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.asr.AudioDecoder
import ai.prinim.prinyal.asr.ModelStore
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.domain.DayLine
import ai.prinim.prinyal.domain.Replacements
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

/**
 * Обработка вечернего ответа (Р-18.1): распознать, сжать в строку, поймать
 * обещание.
 *
 * Отдельный воркер, а не ветка UploadWorker: у дня другой конвейер — нет
 * разбора на пункты, нет возвратов, нет сети как обязательного шага. Общее у
 * них только распознавание, и оно здесь такое же — GigaAM на устройстве,
 * словарь замен применяется сразу.
 */
class DayWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val date = inputData.getString(KEY_DATE) ?: return Result.success()
        val app = PrinyalApp.of(applicationContext)
        val day = app.db.days().byDate(date) ?: return Result.success()

        val transcript = day.transcript ?: run {
            val heard = transcribe(app, File(day.audioPath)) ?: return Result.retry()
            app.db.days().update(day.copy(transcript = heard))
            heard
        }

        if (transcript.isBlank()) {
            // Речи не было. День без слов строки не получает — но и не
            // удаляется: аудио может быть тихим, а не пустым, человек решит сам.
            app.db.days().update(app.db.days().byDate(date)!!.copy(line = ""))
            return Result.success()
        }

        // Модель может быть недоступна — строка соберётся из начала ответа.
        // Правило «слова человека» держит DayLine, а не промпт.
        val result = if (app.settings.llmEnabledNow()) app.llm.day(transcript) else null
        val line = DayLine.of(transcript, result?.line)
        app.db.days().update(app.db.days().byDate(date)!!.copy(line = line))

        // Явное обещание внутри ответа — «напомни…», «не забыть…» — уходит
        // обычной записью в ленту (решение дизайнера, 12b): обещание, сказанное
        // вслух, терять нельзя. Строка дня при этом остаётся целой: пик дня не
        // режется ради дела.
        val task = result?.task?.trim()
        if (!task.isNullOrEmpty() && task.lowercase() in transcript.lowercase()) {
            val noteId = java.util.UUID.randomUUID().toString()
            app.repository.createNote(
                id = noteId,
                audio = File(day.audioPath),
                durationMs = day.durationMs,
                source = CaptureSource.of(SOURCE_DAY),
                createdAt = java.time.Instant.ofEpochMilli(day.createdAt),
            )
            app.repository.saveTranscript(noteId, task, tookMs = 0)
            UploadWorker.enqueue(applicationContext, noteId)
        }

        app.analytics.log(
            "day_answered",
            mapOf("date" to date, "chars" to transcript.length, "task" to (task != null)),
        )
        // Веса больше не нужны: держать их до следующей записи значит
        // ходить по краю OOM всё время, пока человек листает экраны.
        app.releaseAsr()
        return Result.success()
    }

    private suspend fun transcribe(app: PrinyalApp, audio: File): String? {
        if (!audio.exists()) return ""
        // Сначала спрашиваем движок, потом распаковываем. Поднятый движок —
        // сам себе доказательство, что веса на месте; проверять их снова на
        // каждой записи пачки значит лезть в архив APK четыре раза впустую.
        val engine = app.asr() ?: run {
            if (!ModelStore.install(app)) return null
            app.asr() ?: return null
        }
        return runCatching {
            val samples = AudioDecoder.decode(audio)
            val heard = if (samples.isEmpty()) "" else engine.transcribe(samples)
            Replacements.apply(heard, app.db.replacements().all()).text
        }.getOrNull()
    }

    companion object {
        private const val KEY_DATE = "date"
        private const val SOURCE_DAY = "day"

        fun enqueue(context: Context, date: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "day-$date",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DayWorker>()
                    .setInputData(workDataOf(KEY_DATE to date))
                    .build(),
            )
        }
    }
}
