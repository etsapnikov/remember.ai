package ai.prinim.prinyal.capture

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.net.IngestOutcome
import ai.prinim.prinyal.returns.Notifications
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Отправка записей на разбор (PRD §F-3).
 *
 * Очередь переживает перезагрузку и убийство процесса, потому что живёт в базе, а не
 * в памяти: WorkManager здесь — только исполнитель, состояние — в `notes.status`.
 *
 * Пять записей в самолётном режиме → включение сети → все пять разобраны без участия
 * пользователя (приёмка F-3). Именно поэтому воркер за один заход разгребает всю
 * очередь, а не одну запись.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = PrinyalApp.of(applicationContext)
        val baseUrl = app.settings.serverUrlNow()
        val token = app.settings.token

        val pending = app.db.notes().pending()
        if (pending.isEmpty()) return Result.success()

        var parsed = 0
        var retryNeeded = false

        for (note in pending) {
            val audio = File(note.audioPath)
            if (!audio.exists()) {
                // Файла нет — разбирать нечего; запись помечаем, но не удаляем:
                // пусть будет видно, что здесь что-то было.
                app.repository.markFailed(note.id, "audio_missing")
                continue
            }

            app.repository.markQueued(note.id)
            app.repository.bumpAttempts(note.id)

            val outcome = app.api.ingest(
                baseUrl = baseUrl,
                token = token,
                noteId = note.id,
                audio = audio,
                createdAtSeconds = note.createdAt / 1000,
                tzOffsetMinutes = tzOffsetMinutes(note.createdAt),
            )

            when (outcome) {
                is IngestOutcome.Ok -> {
                    app.repository.markSent(note.id)
                    val items = app.repository.applyParse(note.id, outcome.result)
                    parsed++
                    // Одиночную запись показываем сразу; пачку после оффлайна —
                    // одной сводкой ниже, чтобы не завалить шторку.
                    if (pending.size == 1) {
                        Notifications.showUnderstanding(
                            applicationContext, note.id, items, outcome.result.degraded,
                        )
                    }
                }

                is IngestOutcome.Fatal -> app.repository.markFailed(note.id, outcome.code)

                is IngestOutcome.Retryable -> {
                    retryNeeded = true
                    app.db.notes().setStatus(note.id, NoteStatus.QUEUED.wire)
                }
            }
        }

        if (parsed > 1) {
            Notifications.showBatch(applicationContext, parsed, Instant.now())
        }

        return if (retryNeeded) Result.retry() else Result.success()
    }

    private fun tzOffsetMinutes(atMillis: Long): Int {
        val zone = ZoneId.systemDefault()
        return zone.rules.getOffset(Instant.ofEpochMilli(atMillis)).totalSeconds / 60
    }

    companion object {
        private const val WORK_NAME = "prinyal_upload"

        fun enqueue(context: Context, noteId: String? = null) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf("note_id" to noteId))
                .build()

            // APPEND_OR_REPLACE: новая запись во время неудачных ретраев не должна
            // отменять уже стоящую в очереди работу.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
