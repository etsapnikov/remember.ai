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
 * Рассказ о человеке (Р-21.4): пополняет карточку, а не ленту.
 *
 * Раньше «Рассказать» открывало обычный захват, и рассказ про Веру ложился
 * заметкой в «Записи» — вместе с пунктами и возвратами, которых человек не
 * просил. Он добавлял контекст о человеке, а получал дело в плане.
 *
 * Здесь запись только распознаётся, из неё достаются факты и уходят в карточку.
 * Ни заметки, ни пунктов, ни возвратов. Аудио удаляется: хранить нечего —
 * рассказ живёт фактами, а не звуком.
 */
class PersonTellWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val personId = inputData.getString(KEY_PERSON) ?: return Result.success()
        val path = inputData.getString(KEY_AUDIO) ?: return Result.success()
        val app = PrinyalApp.of(applicationContext)
        val person = app.db.people().byId(personId) ?: return Result.success()

        val audio = File(path)
        val heard = transcribe(app, audio) ?: return Result.retry()
        if (heard.isNotBlank()) {
            app.repository.rememberTold(personId, app.llm.personTell(person.name, heard))
            app.analytics.log(
                "person_tell",
                mapOf("person" to personId, "chars" to heard.length),
            )
        }
        runCatching { audio.delete() }
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
        private const val KEY_PERSON = "person"
        private const val KEY_AUDIO = "audio"

        fun enqueue(context: Context, personId: String, audioPath: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "person-tell-$personId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PersonTellWorker>()
                    .setInputData(workDataOf(KEY_PERSON to personId, KEY_AUDIO to audioPath))
                    .build(),
            )
        }
    }
}
