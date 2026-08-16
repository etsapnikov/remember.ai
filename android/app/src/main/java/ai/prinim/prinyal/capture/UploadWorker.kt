package ai.prinim.prinyal.capture

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.asr.AudioDecoder
import ai.prinim.prinyal.asr.ModelStore
import ai.prinim.prinyal.domain.Replacements
import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.net.IngestOutcome
import ai.prinim.prinyal.returns.Notifications
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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

            // Дописанные сегменты распознаём отдельно и склеиваем: разбор
            // должен видеть весь текст сразу, иначе «справка не в школе, а в
            // поликлинике» превратится в новый пункт вместо уточнения.
            val segments = app.db.segments().forNote(note.id)
            val appended = segments.size > 1
            if (appended) {
                segments.filter { it.transcript.isNullOrBlank() }.forEach { segment ->
                    val file = File(segment.audioPath)
                    if (!file.exists()) return@forEach
                    val text = transcribeLocally(app, note.id, file, store = false)
                    if (text != null) app.db.segments().setTranscript(segment.id, text)
                }
            }

            // Распознаём на устройстве и только потом идём в сеть: аудио телефон не
            // покидает, а транскрипт появляется даже когда сети нет вовсе.
            val transcript = if (appended) {
                app.repository.joinedTranscript(note.id)
            } else {
                note.transcript ?: transcribeLocally(app, note.id, audio)
            }

            val outcome = when {
                transcript == null -> IngestOutcome.Retryable("asr_not_ready")
                transcript.isBlank() -> IngestOutcome.Fatal("asr_empty")
                // Адрес сервера задан — идём через свой бэкенд (контур PRD §2).
                // Не задан — разбираем сами: версия работает без сервера.
                baseUrl.isNotBlank() -> app.api.parse(
                    baseUrl = baseUrl,
                    token = token,
                    noteId = note.id,
                    transcript = transcript,
                    createdAtSeconds = note.createdAt / 1000,
                    tzOffsetMinutes = tzOffsetMinutes(note.createdAt),
                )
                else -> app.llm.parse(
                    transcript = transcript,
                    // Живые разделы уходят в промпт, чтобы модель выбирала из
                    // них, а не изобретала синоним уже существующего.
                    topics = app.db.topics().live().map { it.name },
                    // Тот же словарь уходит в промпт: замена чинит написание,
                    // глоссарий — понимание.
                    glossary = Replacements.glossary(app.db.replacements().all()),
                    // Узнанное про людей возвращается в разбор: «Юля —
                    // воспитательница Сони» помогает верно назначить адресата.
                    people = app.db.people().known().map { "${it.name} — ${it.fact}" },
                    // Существующие пункты передаём только при дописывании: на
                    // первом разборе ссылаться не на что, а лишний контекст
                    // сбивает модель.
                    existing = if (appended) {
                        app.db.items().forNote(note.id).map { it.id to it.text }
                    } else {
                        emptyList()
                    },
                    now = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(note.createdAt),
                        java.time.ZoneId.systemDefault(),
                    ),
                    zone = java.time.ZoneId.systemDefault(),
                )
            }

            when (outcome) {
                is IngestOutcome.Ok -> {
                    app.repository.markSent(note.id)
                    // Дописанное идёт через сверку: закрытые пункты обязаны
                    // пережить переразбор.
                    val items = if (appended) {
                        app.repository.applyAppendParse(note.id, outcome.result)
                    } else {
                        app.repository.applyParse(note.id, outcome.result)
                    }
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

    /**
     * Распознавание на устройстве. Транскрипт сразу кладётся в базу: разбор может
     * не состояться из-за сети, но услышанное уже не потеряется и второй раз
     * считаться не будет — GigaAM стоит секунд процессорного времени.
     *
     * @return текст, пустая строка (речи нет) или null, если движок недоступен
     */
    private suspend fun transcribeLocally(
        app: PrinyalApp,
        noteId: String,
        audio: File,
        /** Класть ли результат в заметку: у сегмента своё поле. */
        store: Boolean = true,
    ): String? {
        // Веса распаковываются здесь, перед первым распознаванием: старт
        // приложения не должен ждать 326 МБ.
        if (!ModelStore.ready(app)) {
            if (!ModelStore.install(app)) return null
        }
        val engine = app.asr() ?: return null
        return try {
            val started = System.currentTimeMillis()
            val samples = AudioDecoder.decode(audio)
            val heard = if (samples.isEmpty()) "" else engine.transcribe(samples)
            // Словарь применяется сразу после распознавания: дальше по конвейеру
            // текст уже считается тем, что человек сказал.
            val rules = app.db.replacements().all()
            val fixed = Replacements.apply(heard, rules)
            fixed.hits.forEach { (id, times) -> app.db.replacements().addHits(id, times) }
            if (fixed.hits.isNotEmpty()) {
                app.analytics.log(
                    Analytics.REPLACEMENT_HIT,
                    mapOf("note" to noteId, "rules" to fixed.hits.size),
                )
            }
            val text = fixed.text
            val took = System.currentTimeMillis() - started

            if (store) app.repository.saveTranscript(noteId, text, took)
            Log.i(TAG, "распознал $noteId за ${took}мс, символов ${text.length}")
            text
        } catch (e: Throwable) {
            // Движок не загрузился или упал — аудио цело, попробуем в следующий заход.
            // Пишем причину в свой лог: на прошивках с закрытым логкатом (Huawei)
            // это единственный способ узнать, что именно упало.
            Log.w(TAG, "распознавание $noteId не удалось: ${e.message}")
            app.analytics.log(
                "asr_error",
                mapOf(
                    "note" to noteId,
                    "error" to (e::class.java.name),
                    "msg" to (e.message ?: ""),
                    "at" to (e.stackTrace.firstOrNull()?.toString() ?: ""),
                ),
            )
            null
        }
    }

    private fun tzOffsetMinutes(atMillis: Long): Int {
        val zone = ZoneId.systemDefault()
        return zone.rules.getOffset(Instant.ofEpochMilli(atMillis)).totalSeconds / 60
    }

    companion object {
        private const val TAG = "PrinyalAsr"
        private const val WORK_NAME = "prinyal_upload"

        fun enqueue(context: Context, noteId: String? = null) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                // Сетевого условия нет намеренно: распознавание идёт на устройстве и
                // должно случиться даже в самолётном режиме. Транскрипт появляется
                // сразу, сети ждёт только разбор — он и уйдёт в retry.
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf("note_id" to noteId))
                .build()

            // APPEND_OR_REPLACE: новая запись во время неудачных ретраев не должна
            // отменять уже стоящую в очереди работу.
            submit(context, request, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        /**
         * Пнуть очередь при открытии приложения (PRD §6: «ретраи по backoff до часа,
         * дальше — по открытию приложения»).
         *
         * Без этого запись, пережившая исчерпанный backoff, ждёт следующей записи —
         * то есть молчит ровно тогда, когда человек открыл приложение посмотреть,
         * почему тихо.
         *
         * KEEP, а не APPEND: если работа уже стоит, второй заход не нужен.
         */
        fun kick(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            submit(context, request, ExistingWorkPolicy.KEEP)
        }

        /**
         * Постановка задачи не имеет права уронить процесс.
         *
         * `kick` зовётся из `Application.onCreate`, где WorkManager может быть ещё не
         * поднят: исключение оттуда убило бы приложение при старте — то есть отняло бы
         * у человека кнопку записи ради задачи, которая подождёт до следующего раза.
         */
        private fun submit(
            context: Context,
            request: androidx.work.OneTimeWorkRequest,
            policy: ExistingWorkPolicy,
        ) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
            }.onFailure { error ->
                Log.w(TAG, "не удалось поставить задачу разбора: ${error.message}")
            }
        }
    }
}
