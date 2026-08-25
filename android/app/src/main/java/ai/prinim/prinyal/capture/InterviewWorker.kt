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
 * Ответ на вопрос пинг-понга (Р-19.1).
 *
 * Отдельный конвейер, а не общий разбор — и это главная правка версии. Раньше
 * ответ уходил обычным дописыванием: сегмент цеплялся к записи, и **вся** она
 * разбиралась заново. Из ответа на вопрос рождались новые пункты, старые
 * пересобирались, запись могла поделиться надвое — вместо разговора об идее
 * получалась перетряска дел. Владелец назвал это «сломано», и он прав: ответ на
 * вопрос — это мысль, а не поручение.
 *
 * Здесь ответ только распознаётся и дописывается в тело заметки. Пункты,
 * возвраты и раздел не трогаются вовсе.
 */
class InterviewWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE) ?: return Result.success()
        val app = PrinyalApp.of(applicationContext)
        val note = app.db.notes().byId(noteId) ?: return Result.success()

        // Ответ — последний сегмент записи: его положило дописывание.
        val segment = app.db.segments().forNote(noteId).maxByOrNull { it.seq }
            ?: return Result.success()

        val answer = segment.transcript ?: run {
            val heard = transcribe(app, File(segment.audioPath)) ?: return Result.retry()
            app.db.segments().insert(segment.copy(transcript = heard))
            heard
        }
        if (answer.isBlank()) {
            // Промолчал — вопрос остаётся заданным, тело не трогаем.
            app.repository.markInterviewIdle(noteId)
            return Result.success()
        }

        val question = app.db.questions().forNote(noteId).maxByOrNull { it.askedAt }?.text.orEmpty()
        val block = app.llm.interviewAnswer(
            idea = note.transcript.orEmpty(),
            question = question,
            answer = answer,
        )
        app.repository.appendInterviewRound(noteId, question, block ?: answer)
        app.analytics.log(
            "interview_answer",
            mapOf("note" to noteId, "chars" to answer.length, "polished" to (block != null)),
        )

        // Круг замыкается здесь, в фоне: следующий вопрос строится по телу, в
        // котором ответ **уже** лежит. Прошлая версия спрашивала с экрана,
        // не дождавшись записи, и получала тот же вопрос слово в слово.
        app.repository.askNext(noteId)
        // Веса больше не нужны: держать их до следующей записи значит
        // ходить по краю OOM всё время, пока человек листает экраны.
        app.releaseAsr()
        return Result.success()
    }

    private suspend fun transcribe(app: PrinyalApp, audio: File): String? {
        if (!audio.exists()) return ""
        if (!ModelStore.install(app)) return null
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
                "interview-$noteId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<InterviewWorker>()
                    .setInputData(workDataOf(KEY_NOTE to noteId))
                    .build(),
            )
        }
    }
}
