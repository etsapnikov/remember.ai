package ai.prinim.prinyal.capture

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.asr.AudioDecoder
import ai.prinim.prinyal.asr.ModelStore
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
 * Конец разговора об идее (Р-20.2, макет 13c): из ответа рождается дело.
 *
 * Дело живёт **пунктом в той же заметке**, а не отдельной записью: разговор и
 * его вывод разъехались бы по ленте, и «выросло из разговора» пришлось бы
 * объяснять ссылкой.
 *
 * Дела может и не выйти — «не знаю, надо подумать». Это честная концовка, а не
 * сбой: заметка остаётся идеей, и продукт про этот разговор больше не
 * спрашивает.
 */
class FirstStepWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE) ?: return Result.success()
        val app = PrinyalApp.of(applicationContext)
        val note = app.db.notes().byId(noteId) ?: return Result.success()
        val segment = app.db.segments().forNote(noteId).maxByOrNull { it.seq }
            ?: return Result.success()

        val answer = segment.transcript ?: run {
            val heard = transcribe(app, File(segment.audioPath)) ?: return Result.retry()
            app.db.segments().insert(segment.copy(transcript = heard))
            heard
        }
        app.repository.finishInterviewWithStep(
            noteId = noteId,
            step = if (answer.isBlank()) null else app.llm.firstStep(
                idea = app.repository.joinedTranscript(noteId).ifBlank { note.transcript.orEmpty() },
                answer = answer,
                now = java.time.LocalDateTime.now(),
                zone = java.time.ZoneId.systemDefault(),
            ),
        )
        // Веса больше не нужны: держать их до следующей записи значит
        // ходить по краю OOM всё время, пока человек листает экраны.
        app.releaseAsr()
        return Result.success()
    }

    private suspend fun transcribe(app: PrinyalApp, audio: File): String? {
        if (!audio.exists()) return ""
        if (!ModelStore.ready(app)) {
            if (!ModelStore.install(app)) return null
        }
        val engine = app.asr() ?: return null
        return runCatching {
            val samples = AudioDecoder.decode(audio)
            val heard = if (samples.isEmpty()) "" else engine.transcribe(samples)
            Replacements.apply(heard, app.db.replacements().all()).text
        }.getOrNull()
    }

    companion object {
        private const val KEY_NOTE = "note"

        fun enqueue(context: Context, noteId: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "first-step-$noteId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FirstStepWorker>()
                    .setInputData(workDataOf(KEY_NOTE to noteId))
                    .build(),
            )
        }
    }
}
