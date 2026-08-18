package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.LinkEntity
import ai.prinim.prinyal.data.NoteEntity
import ai.prinim.prinyal.data.TopicEntity
import ai.prinim.prinyal.data.TopicKind
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.data.PersonEntity
import ai.prinim.prinyal.data.SegmentEntity
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.returns.ReturnScheduler
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Склейка захвата, разбора и возвратов. Здесь живут решения, которые нельзя доверить
 * ни модели, ни UI: что считать состоявшимся возвратом, когда перепланировать, что
 * делать с записью, которую не удалось разобрать.
 */
class NoteRepository(
    private val db: PrinyalDb,
    private val settings: Settings,
    private val analytics: Analytics,
    private val scheduler: ReturnScheduler,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // --- захват ---

    /** Запись немедленно попадает в базу: очередь переживает убийство процесса (F-3). */
    suspend fun createNote(
        id: String,
        audio: File,
        durationMs: Long,
        source: CaptureSource,
        createdAt: Instant = Instant.now(),
        /** Пик громкости за запись: по нему видно, не тих ли микрофон. */
        peakAmplitude: Int = 0,
    ) {
        db.notes().insert(
            NoteEntity(
                id = id,
                createdAt = createdAt.toEpochMilli(),
                audioPath = audio.absolutePath,
                status = NoteStatus.RECORDED.wire,
                durationMs = durationMs,
                source = source.wire,
            )
        )
        analytics.log(
            Analytics.CAPTURE_STOP,
            mapOf(
                "note" to id,
                "source" to source.wire,
                "ms" to durationMs,
                "peak" to peakAmplitude,
            ),
        )
    }

    // --- разбор ---

    /**
     * Разбор пришёл. Пункты и расписание возвратов пересобираются целиком: повторный
     * разбор той же записи не должен оставлять хвост от прошлого.
     */
    suspend fun applyParse(noteId: String, result: ParseResult): List<ItemEntity> {
        val note = db.notes().byId(noteId) ?: return emptyList()

        // Что человек поставил рукой, переразбор не имеет права стереть.
        //
        // Переразбор пересоздаёт пункты с нуля — иначе правленый транскрипт дал
        // бы смесь старого и нового. Но ручная дата это не разбор, а решение
        // человека: он сказал «верну десятого», и модель не вправе с ним
        // спорить. Правило то же, что у топика (Р-15.7).
        //
        // Сверяем по нормализованному тексту: id у нового пункта другой, а
        // формулировка после переразбора обычно та же.
        val handSet = db.items().forNote(noteId)
            .filter { it.edited && DueKind.of(it.dueKind) == DueKind.EXACT && it.dueAt != null }
            .associateBy({ normalizeText(it.text) }, { it.dueAt!! })

        dropSchedule(noteId)

        val windows = settings.windowsNow()
        val recordedAt = Instant.ofEpochMilli(note.createdAt)
        val now = Instant.now()

        val items = result.items.mapIndexed { index, parsed ->
            val kept = handSet[normalizeText(parsed.text)]
            ItemEntity(
                id = newId(),
                noteId = noteId,
                type = parsed.type.wire,
                text = parsed.text,
                who = parsed.who,
                dueKind = if (kept != null) DueKind.EXACT.wire else parsed.dueKind.wire,
                window = if (kept != null) null else parsed.window?.wire,
                dueAt = kept ?: parsed.dueAt,
                state = ItemState.PLANNED.wire,
                confidence = parsed.confidence.wire,
                rawSpan = parsed.rawSpan,
                position = index,
                // Пометка переезжает вместе с датой: иначе следующий переразбор
                // сочтёт пункт нетронутым и сотрёт то, что мы только что спасли.
                edited = kept != null,
            )
        }
        db.items().insertAll(items)

        items.forEach { item -> planReturn(item, recordedAt, windows, now) }

        val topicId = resolveTopic(note, result.topic)
        rememberPeople(result.entities)

        db.notes().update(
            note.copy(
                transcript = result.transcript,
                status = NoteStatus.PARSED.wire,
                degraded = result.degraded,
                topicId = topicId ?: note.topicId,
                topicSource = when {
                    // Ручной выбор заморожен: переразбор его не перезаписывает,
                    // иначе правка руками стала бы вечной работой (scope §0 п.4).
                    note.topicSource == TopicSource.USER.wire -> note.topicSource
                    topicId != null -> TopicSource.LLM.wire
                    else -> note.topicSource
                },
                noteKind = result.noteKind?.wire ?: note.noteKind,
                // Переразбор пересобирает тело: правка транскрипта обязана его
                // инвалидировать, иначе «Собрано» будет описывать прошлый текст.
                bodyMd = result.bodyMd,
            )
        )
        applyLinks(noteId, result.links)
        result.second?.let { second -> splitOff(note, second, recordedAt, windows, now) }

        analytics.log(
            Analytics.PARSE_OK,
            mapOf(
                "note" to noteId,
                "items" to items.size,
                "asr_ms" to result.asrMs,
                "llm_ms" to result.llmMs,
                "retries" to result.llmRetries,
                "degraded" to result.degraded,
            ),
        )
        return items
    }

    /**
     * Транскрипт с устройства. Кладём отдельно от разбора: услышанное не должно
     * зависеть от того, дошли ли мы до сети, и пересчитывать его второй раз незачем.
     */
    suspend fun saveTranscript(noteId: String, transcript: String, tookMs: Long) {
        val note = db.notes().byId(noteId) ?: return
        db.notes().update(note.copy(transcript = transcript))
        analytics.log(
            "asr_done",
            mapOf("note" to noteId, "ms" to tookMs, "chars" to transcript.length),
        )
    }

    /**
     * Правленый человеком транскрипт (спека R1.2 §15). Статус сбрасывается в
     * `recorded`, чтобы очередь взяла запись в работу; ASR при этом пропускается —
     * транскрипт уже есть, и в разбор уйдёт именно правленый текст.
     */
    suspend fun replaceTranscript(noteId: String, transcript: String) {
        val note = db.notes().byId(noteId) ?: return
        val before = note.transcript.orEmpty()
        db.notes().update(
            note.copy(transcript = transcript.trim(), status = NoteStatus.RECORDED.wire)
        )
        analytics.log(
            "transcript_edit",
            mapOf("note" to noteId, "was" to before.length, "now" to transcript.trim().length),
        )
    }

    /**
     * Разбор не состоялся совсем (ASR). Аудио цело, запись видна в ленте и
     * перезапускаема — «не смог» не равно «потерял» (§6).
     */
    suspend fun markFailed(noteId: String, code: String) {
        val status = if (code.startsWith("asr")) NoteStatus.FAILED_ASR else NoteStatus.FAILED_LLM
        val note = db.notes().byId(noteId) ?: return
        db.notes().update(note.copy(status = status.wire, degraded = code))
        analytics.log(Analytics.PARSE_FAIL, mapOf("note" to noteId, "code" to code))
    }

    suspend fun markQueued(noteId: String) =
        db.notes().setStatus(noteId, NoteStatus.QUEUED.wire)

    suspend fun markSent(noteId: String) =
        db.notes().setStatus(noteId, NoteStatus.SENT.wire)

    suspend fun bumpAttempts(noteId: String) = db.notes().bumpAttempts(noteId)

    /**
     * Связи, найденные разбором (Р-15.11).
     *
     * Перед записью сносим прежние связи **этой** заметки — переразбор обязан
     * пересобрать их так же, как пункты, иначе исправленный транскрипт оставит
     * за собой связь, которой в нём уже нет. Сносим только те, где заметка —
     * источник: связь, найденная с другого конца, принадлежит той заметке, и
     * стирать чужой вывод мы не вправе.
     */
    private suspend fun applyLinks(noteId: String, links: List<LinkValidator.Link>) {
        db.links().dropFrom(noteId)
        if (links.isEmpty()) return
        val now = Instant.now().toEpochMilli()
        db.links().insertAll(
            links.mapNotNull { link ->
                // Кандидат мог быть удалён, пока шёл разбор: минута — достаточно
                // долго, чтобы человек успел смахнуть запись.
                db.notes().byId(link.ref)?.let {
                    LinkEntity(
                        id = newId(),
                        fromNoteId = noteId,
                        toNoteId = link.ref,
                        reason = link.reason.wire,
                        confidence = link.confidence.wire,
                        createdAt = now,
                    )
                }
            }
        )
    }

    /**
     * Новая формулировка застрявшего пункта (Р-15.8).
     *
     * Прежняя не теряется: она уходит в `previous_text` и остаётся видна на
     * карточке. Пункт помечается как тронутый рукой — переразбор не имеет права
     * вернуть старую формулировку, иначе развилка отменялась бы сама собой.
     *
     * Возвраты не перепланируются: человек попросил другое дело, а не другое
     * время, и назначать напоминание самим значило бы решать за него.
     */
    suspend fun reword(itemId: String, text: String) {
        val item = db.items().byId(itemId) ?: return
        db.items().update(
            item.copy(
                text = text,
                previousText = item.previousText ?: item.text,
                edited = true,
                state = ItemState.PLANNED.wire,
            )
        )
        analytics.log("item_reworded", mapOf("item" to itemId, "was" to item.text.length))
    }

    /**
     * Запись оказалась командой, а не заметкой (Р-15.13).
     *
     * Аудио и транскрипт удаляются: команда своей ценности не имеет, а её след
     * в ленте — мусор. Событие в аналитике остаётся: по нему видно, что человек
     * командой пользуется, даже когда результат его не устроил.
     */
    suspend fun markCommand(noteId: String, topic: String): List<ContextPack.Source> {
        val note = db.notes().byId(noteId) ?: return emptyList()
        analytics.log("voice_command", mapOf("note" to noteId, "topic" to topic))
        runCatching { File(note.audioPath).delete() }
        db.notes().delete(noteId)

        // Тема названа голосом и в косвенном падеже — сопоставляем по основе.
        // Точного совпадения имён ждать нельзя: «по авторизации» никогда не
        // совпадёт с разделом «Авторизация» буква в букву.
        val stem = topic.lowercase().take(STEM)
        val topicId = db.topics().live()
            .firstOrNull { it.name.lowercase().take(STEM) == stem }
            ?.id
        val notes = db.notes().all().filter {
            if (topicId != null) it.topicId == topicId
            // Раздел не нашёлся — ищем по словам самих записей: тема могла
            // никогда не становиться разделом, а материал по ней есть.
            else it.transcript.orEmpty().lowercase().contains(stem)
        }
        return notes.map { ContextPack.Source(it, db.items().forNote(it.id)) }
    }



    /**
     * Переезд группы заметок в новый раздел (Р-15.12).
     *
     * Источник помечается как `repair`, а не `user`: человек согласился с
     * предложением, но раскладку придумал продукт, и через месяц разницу между
     * «я так решил» и «я не возражал» будет видно только по этой пометке.
     */
    suspend fun moveToNewTopic(noteIds: List<String>, name: String) {
        val clean = name.trim().replaceFirstChar { it.uppercase() }
        if (clean.isBlank() || noteIds.isEmpty()) return
        val norm = Replacements.norm(clean)
        val topic = db.topics().byNorm(norm) ?: TopicEntity(
            id = newId(),
            name = clean,
            nameNorm = norm,
            kind = TopicKind.MANUAL.wire,
            createdAt = Instant.now().toEpochMilli(),
        ).also { db.topics().insert(it) }

        noteIds.forEach { id ->
            db.notes().setTopic(id, topic.id, TopicSource.REPAIR.wire)
        }
        analytics.log(
            "structure_repair",
            mapOf("topic" to topic.name, "notes" to noteIds.size),
        )
    }

    // --- планирование ---

    private suspend fun planReturn(
        item: ItemEntity,
        recordedAt: Instant,
        windows: Scheduler.Windows,
        now: Instant,
        attempt: Int = 1,
    ) {
        val dueKind = DueKind.of(item.dueKind)
        if (!ReturnPolicy.schedules(ItemType.of(item.type), dueKind)) return

        val at = when (dueKind) {
            // Миллисекунды. Здесь жила вторая половина той же ошибки единиц:
            // валидатор отдавал секунды, планировщик читал секунды, а экран —
            // миллисекунды. Починив экран, легко сломать расписание, поэтому
            // единица теперь одна на всём пути и закреплена тестом.
            DueKind.EXACT -> item.dueAt?.let(Instant::ofEpochMilli)
            DueKind.WINDOW -> Window.of(item.window)?.let { window ->
                Scheduler.scheduleFor(window, recordedAt, windows, zone, now)
            }
            DueKind.NONE -> null
        } ?: return

        // Точное время из прошлого (запись пролежала в очереди) — не звоним сразу
        // ночью, а уходим в ближайшее окно.
        val safeAt = if (at.isAfter(now)) at else Scheduler.nextWindowAfter(now, windows, zone)

        // Возврат в прошлом — не «сработает сразу», а не сработает вовсе или
        // прозвонит ночью. Инвариант держим здесь, у единственной точки записи
        // расписания, а не надеемся на аккуратность вызывающих (Р-15.7).
        check(safeAt.isAfter(now)) { "возврат назначен в прошлое: $safeAt" }

        val entity = ReturnEntity(
            id = newId(),
            itemId = item.id,
            scheduledAt = safeAt.toEpochMilli(),
            attempt = attempt,
        )
        db.returns().insert(entity)
        scheduler.schedule(entity.id, safeAt)
    }

    /**
     * Имя раздела от модели → строка в `topics`.
     *
     * Три правила, каждое из своей беды:
     *  - ручной топик не трогаем вовсе — человек уже решил;
     *  - совпадение ищем по нормализованному имени, иначе «Работа» и «работа»
     *    разъедутся в два раздела на второй записи;
     *  - авто-разделов не больше [MAX_AUTO_TOPICS]: без потолка модель заводит
     *    новый почти под каждую запись, и «разделы» превращаются в шум. Упёрлись
     *    в потолок — незнакомое имя отбрасывается, заметка остаётся без раздела.
     *
     * @return id раздела или null, если относить не к чему
     */
    private suspend fun resolveTopic(note: NoteEntity, name: String?): String? {
        if (note.topicSource == TopicSource.USER.wire) return null
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty()) return null

        val norm = clean.lowercase().replace(Regex("\\s+"), " ")
        db.topics().byNorm(norm)?.let { return it.id }

        if (db.topics().autoCount() >= MAX_AUTO_TOPICS) return null

        db.topics().insert(
            TopicEntity(
                id = newId(),
                name = clean,
                nameNorm = norm,
                kind = TopicKind.AUTO.wire,
                createdAt = System.currentTimeMillis(),
            )
        )
        analytics.log(Analytics.TOPIC_ASSIGNED, mapOf("topic" to clean, "new" to true))
        return db.topics().byNorm(norm)?.id
    }

    /**
     * Учесть людей, упомянутых в записи.
     *
     * Считаем появления, а не заводим карточки: продукт не ведёт справочник
     * людей, ему нужно только понять, о ком он ничего не знает и кто при этом
     * повторяется.
     */
    /**
     * Завести вторую заметку из той же записи (Р-15.5).
     *
     * Аудио и транскрипт у половин общие — запись была одна, и притворяться,
     * что их две, значило бы врать в плеере. Разделены только пункты: именно
     * они живут своей жизнью, возвращаются и закрываются.
     *
     * Ссылка двусторонняя, чтобы «склеить обратно» работало с любой половины.
     */
    private suspend fun splitOff(
        note: NoteEntity,
        second: ParseResult,
        recordedAt: Instant,
        windows: Scheduler.Windows,
        now: Instant,
    ) {
        val secondId = newId()
        db.notes().insert(
            note.copy(
                id = secondId,
                // Плюс миллисекунда: в ленте половины должны идти подряд и в
                // понятном порядке, а не спорить за одну и ту же секунду.
                createdAt = note.createdAt + 1,
                status = NoteStatus.PARSED.wire,
                topicId = resolveTopic(note.copy(id = secondId, topicId = null,
                    topicSource = TopicSource.NONE.wire), second.topic),
                topicSource = TopicSource.LLM.wire,
                noteKind = second.noteKind?.wire,
                bodyMd = second.bodyMd,
                siblingId = note.id,
            )
        )
        val items = second.items.mapIndexed { index, parsed ->
            ItemEntity(
                id = newId(),
                noteId = secondId,
                type = parsed.type.wire,
                text = parsed.text,
                who = parsed.who,
                dueKind = parsed.dueKind.wire,
                window = parsed.window?.wire,
                dueAt = parsed.dueAt,
                state = ItemState.PLANNED.wire,
                confidence = parsed.confidence.wire,
                rawSpan = parsed.rawSpan,
                position = index,
            )
        }
        db.items().insertAll(items)
        items.forEach { planReturn(it, recordedAt, windows, now) }
        db.notes().setSibling(note.id, secondId)
        analytics.log(Analytics.NOTE_SPLIT, mapOf("note" to note.id, "second" to secondId))
    }

    /**
     * Склеить половины обратно (Р-15.5).
     *
     * Пункты переезжают **со своими состояниями**: закрытое остаётся закрытым.
     * Склейка — исправление разметки, а не повод переиграть прожитое.
     */
    suspend fun mergeSiblings(noteId: String) {
        val note = db.notes().byId(noteId) ?: return
        val siblingId = note.siblingId ?: return
        val sibling = db.notes().byId(siblingId) ?: return

        // Оставляем ту половину, что старше: она первая в ленте и первая в речи.
        val keep = if (note.createdAt <= sibling.createdAt) note else sibling
        val drop = if (keep.id == note.id) sibling else note

        var position = db.items().forNote(keep.id).size
        db.items().forNote(drop.id).forEach { item ->
            db.items().update(item.copy(noteId = keep.id, position = position++))
        }
        db.notes().update(keep.copy(siblingId = null))
        db.notes().delete(drop.id)
        analytics.log(Analytics.NOTE_MERGE, mapOf("note" to keep.id))
    }

    /**
     * Дописать к заметке новый сегмент (Р-14.3).
     *
     * Заметка становится многосегментной: транскрипт — конкатенация сегментов с
     * меткой времени между ними. Метка ставится начиная со второго сегмента: у
     * первого её нет, иначе обычная запись обрастает служебной строкой ни за что.
     */
    suspend fun appendSegment(noteId: String, audio: File, at: Instant = Instant.now()): String {
        val seq = db.segments().nextSeq(noteId)
        // Первый сегмент заводится задним числом для записей, сделанных до 1.0.1.
        if (seq == 0) {
            val note = db.notes().byId(noteId)
            if (note != null) {
                db.segments().insert(
                    SegmentEntity(
                        id = newId(),
                        noteId = noteId,
                        seq = 0,
                        audioPath = note.audioPath,
                        transcript = note.transcript,
                        createdAt = note.createdAt,
                    )
                )
            }
        }
        val id = newId()
        db.segments().insert(
            SegmentEntity(
                id = id,
                noteId = noteId,
                seq = maxOf(seq, 1),
                audioPath = audio.absolutePath,
                createdAt = at.toEpochMilli(),
            )
        )
        // Статус возвращается в очередь: дописанное надо распознать и разобрать.
        db.notes().setStatus(noteId, NoteStatus.RECORDED.wire)
        return id
    }

    /** Транскрипт заметки целиком: сегменты по порядку, разделённые меткой. */
    suspend fun joinedTranscript(noteId: String): String =
        db.segments().forNote(noteId)
            .mapNotNull { it.transcript?.takeIf(String::isNotBlank) }
            .joinToString("\n")

    /**
     * Применить разбор дописанной заметки (Р-14.3).
     *
     * Отличается от [applyParse] одним, но решающим: старые пункты не сносятся.
     * Полная замена здесь означала бы, что «сделал» и «не надо» стираются каждым
     * дописыванием, — а это ровно то, ради чего человек и отвечает на возвраты.
     *
     * Сверка не удалась — откатываемся в безопасное: старые пункты не трогаем,
     * добавляем только то, что модель принесла нового. Дубль человек заметит и
     * уберёт, воскресшее закрытое дело подорвёт доверие ко всей петле.
     */
    suspend fun applyAppendParse(noteId: String, result: ParseResult): List<ItemEntity> {
        val note = db.notes().byId(noteId) ?: return emptyList()
        val existing = db.items().forNote(noteId)
        val plan = Reconcile.plan(existing, result.items, result.items.map { it.ref })

        val windows = settings.windowsNow()
        val recordedAt = Instant.ofEpochMilli(note.createdAt)
        val now = Instant.now()
        var position = existing.size
        val touched = mutableListOf<ItemEntity>()

        plan.actions.forEach { action ->
            when (action) {
                is Reconcile.Action.Add -> {
                    val item = ItemEntity(
                        id = newId(),
                        noteId = noteId,
                        type = action.item.type.wire,
                        text = action.item.text,
                        who = action.item.who,
                        dueKind = action.item.dueKind.wire,
                        window = action.item.window?.wire,
                        dueAt = action.item.dueAt,
                        state = ItemState.PLANNED.wire,
                        confidence = action.item.confidence.wire,
                        rawSpan = action.item.rawSpan,
                        position = position++,
                    )
                    db.items().insertAll(listOf(item))
                    planReturn(item, recordedAt, windows, now)
                    touched += item
                }

                is Reconcile.Action.Update -> {
                    val current = db.items().byId(action.id) ?: return@forEach
                    val updated = current.copy(
                        text = action.item.text,
                        window = action.item.window?.wire,
                        dueKind = action.item.dueKind.wire,
                        dueAt = action.item.dueAt,
                    )
                    db.items().update(updated)
                    // Возврат пересчитывается: окно могло поехать.
                    db.returns().dropPending(updated.id)
                    planReturn(updated, recordedAt, windows, now)
                    touched += updated
                }
            }
        }

        db.notes().update(
            note.copy(
                transcript = result.transcript,
                status = NoteStatus.PARSED.wire,
                degraded = result.degraded,
                bodyMd = result.bodyMd ?: note.bodyMd,
            )
        )
        analytics.log(
            Analytics.NOTE_APPEND,
            mapOf(
                "note" to noteId,
                "added" to plan.actions.count { it is Reconcile.Action.Add },
                "updated" to plan.actions.count { it is Reconcile.Action.Update },
                "rejected" to plan.rejected.size,
            ),
        )
        return touched
    }

    /**
     * Ретро-разбор: взять из ответа только раздел и вид записи.
     *
     * Пункты не трогаем намеренно. Старые заметки уже прожиты — часть закрыта,
     * часть поправлена руками, — и переразбор ради структуры переписал бы всё
     * это. Раскладываем по разделам, прошлое оставляем как есть.
     */
    suspend fun applyTopicOnly(noteId: String, result: ParseResult): Boolean {
        val note = db.notes().byId(noteId) ?: return false
        val topicId = resolveTopic(note, result.topic) ?: return false
        db.notes().update(
            note.copy(
                topicId = topicId,
                topicSource = TopicSource.LLM.wire,
                noteKind = result.noteKind?.wire ?: note.noteKind,
            )
        )
        return true
    }

    private suspend fun rememberPeople(names: List<String>) {
        names.forEach { name ->
            val clean = name.trim()
            if (clean.isEmpty()) return@forEach
            val norm = clean.lowercase()
            val existing = db.people().byNorm(norm)
            if (existing == null) {
                db.people().insert(
                    PersonEntity(
                        id = newId(),
                        name = clean,
                        nameNorm = norm,
                        firstSeen = System.currentTimeMillis(),
                    )
                )
            } else {
                db.people().sawAgain(existing.id)
            }
        }
    }

    private suspend fun dropSchedule(noteId: String) {
        db.items().forNote(noteId).forEach { item ->
            db.returns().forItem(item.id)
                .filter { it.firedAt == null }
                .forEach { scheduler.cancel(it.id) }
            db.returns().dropPending(item.id)
        }
        db.items().deleteForNote(noteId)
    }

    // --- действия пользователя над айтемом ---

    suspend fun editItem(
        itemId: String,
        text: String? = null,
        type: ItemType? = null,
        window: Window? = null,
        /** Ручная дата возврата (Р-15.7). Побеждает окно: человек назвал день. */
        exactAt: Long? = null,
        clearSchedule: Boolean = false,
    ) {
        val item = db.items().byId(itemId) ?: return
        val note = db.notes().byId(item.noteId) ?: return

        val updated = item.copy(
            text = text?.trim()?.takeIf { it.isNotEmpty() } ?: item.text,
            type = type?.wire ?: item.type,
            window = when {
                clearSchedule -> null
                exactAt != null -> null
                window != null -> window.wire
                else -> item.window
            },
            dueKind = when {
                clearSchedule -> DueKind.NONE.wire
                exactAt != null -> DueKind.EXACT.wire
                window != null -> DueKind.WINDOW.wire
                else -> item.dueKind
            },
            dueAt = when {
                clearSchedule -> null
                exactAt != null -> exactAt
                window != null -> null
                else -> item.dueAt
            },
            edited = true,
        )
        db.items().update(updated)

        // Правка окна пересчитывает возврат немедленно (приёмка F-5).
        db.returns().forItem(itemId).filter { it.firedAt == null }
            .forEach { scheduler.cancel(it.id) }
        db.returns().dropPending(itemId)
        planReturn(
            updated,
            Instant.ofEpochMilli(note.createdAt),
            settings.windowsNow(),
            Instant.now(),
        )

        analytics.log(
            Analytics.EDIT_ITEM,
            mapOf("item" to itemId, "type" to updated.type, "window" to updated.window),
        )
    }

    /** «Не надо» — один тап, без диалогов. */
    suspend fun dismissItem(itemId: String, fromReturn: Boolean = false) {
        setStateAndStop(itemId, ItemState.DISMISSED)
        analytics.log(
            Analytics.RETURN_ACTION,
            mapOf("item" to itemId, "action" to "dismiss", "from_return" to fromReturn),
        )
    }

    suspend fun markDone(itemId: String) {
        setStateAndStop(itemId, ItemState.DONE)
        analytics.log(Analytics.RETURN_ACTION, mapOf("item" to itemId, "action" to "done"))
    }

    suspend fun buryItem(itemId: String) {
        setStateAndStop(itemId, ItemState.EXPIRED)
        analytics.log(Analytics.MISS_ITEM, mapOf("item" to itemId, "reason" to "manual"))
    }

    /** Undo похорон из снекбара: пункт возвращается в план вместе с возвратом. */
    suspend fun unburyItem(itemId: String) {
        val item = db.items().byId(itemId) ?: return
        val note = db.notes().byId(item.noteId) ?: return
        db.items().setState(itemId, ItemState.PLANNED.wire)
        planReturn(
            item.copy(state = ItemState.PLANNED.wire),
            Instant.ofEpochMilli(note.createdAt),
            settings.windowsNow(),
            Instant.now(),
        )
        analytics.log("bury_undo", mapOf("item" to itemId))
    }

    /**
     * «Позже»: время не выбирается пользователем — код ставит следующее окно и
     * возвращает его, чтобы подтверждение назвало конкретный момент (F-6).
     */
    suspend fun snooze(itemId: String): Instant? {
        val item = db.items().byId(itemId) ?: return null
        val at = Scheduler.nextWindowAfter(Instant.now(), settings.windowsNow(), zone)

        db.items().setState(itemId, ItemState.SNOOZED.wire)
        val entity = ReturnEntity(
            id = newId(),
            itemId = itemId,
            scheduledAt = at.toEpochMilli(),
            attempt = 1,
        )
        db.returns().insert(entity)
        scheduler.schedule(entity.id, at)

        analytics.log(Analytics.RETURN_ACTION, mapOf("item" to itemId, "action" to "later"))
        return at
    }

    private suspend fun setStateAndStop(itemId: String, state: ItemState) {
        db.items().setState(itemId, state.wire)
        db.returns().forItem(itemId).filter { it.firedAt == null }
            .forEach { scheduler.cancel(it.id) }
        db.returns().dropPending(itemId)
    }

    // --- удаление записи (спека R1.1 §2.2) ---

    /**
     * Мягкое удаление: запись скрывается сразу, алармы снимаются сразу, но пока
     * живёт снекбар, всё можно вернуть. Возвраты не трогаем в базе — их
     * восстановление после undo должно попасть на прежние места.
     */
    suspend fun softDeleteNote(noteId: String) {
        db.items().forNote(noteId).forEach { item ->
            db.returns().forItem(item.id)
                .filter { it.firedAt == null }
                .forEach { scheduler.cancel(it.id) }
        }
        db.notes().softDelete(noteId, Instant.now().toEpochMilli())
        analytics.log("note_delete", mapOf("note" to noteId))
    }

    /** Undo из снекбара: запись, пункты и несработавшие возвраты — на прежние места. */
    suspend fun restoreNote(noteId: String) {
        db.notes().undelete(noteId)
        val now = Instant.now()
        db.items().forNote(noteId).forEach { item ->
            db.returns().forItem(item.id)
                .filter { it.firedAt == null }
                .forEach { entity ->
                    val at = Instant.ofEpochMilli(entity.scheduledAt)
                    scheduler.schedule(entity.id, if (at.isAfter(now)) at else now.plusSeconds(60))
                }
        }
        analytics.log("note_delete_undo", mapOf("note" to noteId))
    }

    /** Окончательная зачистка: снекбар истёк или экран покинут. Отсюда возврата нет. */
    suspend fun purgeDeleted() {
        db.notes().softDeleted().forEach { note ->
            runCatching { File(note.audioPath).delete() }
            // Пункты и возвраты уходят каскадом по FK.
            db.notes().delete(note.id)
        }
    }

    /** Групповая уборка: все записи без пунктов одним махом, с тем же undo. */
    suspend fun sweepJunk(): List<String> {
        val junk = db.notes().junk()
        junk.forEach { softDeleteNote(it.id) }
        analytics.log("junk_sweep", mapOf("count" to junk.size))
        return junk.map { it.id }
    }

    // --- возвраты ---

    /**
     * Второй заход по проигнорированному возврату. Третьего нет: дальше `expired`,
     * который ждёт R2-разбора, а не пилит пользователя (F-6).
     */
    suspend fun scheduleSecondAttempt(returnId: String) {
        val fired = db.returns().byId(returnId) ?: return
        if (fired.attempt >= 2) {
            db.items().setState(fired.itemId, ItemState.EXPIRED.wire)
            analytics.log(
                Analytics.MISS_ITEM,
                mapOf("item" to fired.itemId, "reason" to "no_answer"),
            )
            return
        }

        val at = Scheduler.nextWindowAfter(Instant.now(), settings.windowsNow(), zone)
        val entity = ReturnEntity(
            id = newId(),
            itemId = fired.itemId,
            scheduledAt = at.toEpochMilli(),
            attempt = fired.attempt + 1,
        )
        db.returns().insert(entity)
        scheduler.schedule(entity.id, at)
    }

    suspend fun markFired(returnId: String) {
        val entity = db.returns().byId(returnId) ?: return
        db.returns().update(entity.copy(firedAt = Instant.now().toEpochMilli()))
        db.items().setState(entity.itemId, ItemState.RETURNED.wire)
        analytics.log(
            Analytics.RETURN_FIRED,
            mapOf(
                "return" to returnId,
                "item" to entity.itemId,
                "attempt" to entity.attempt,
                // Расхождение плана и факта — данные о прошивке (§9, риск 1).
                "drift_ms" to (Instant.now().toEpochMilli() - entity.scheduledAt),
            ),
        )
    }

    suspend fun recordAction(returnId: String, action: String) {
        val entity = db.returns().byId(returnId) ?: return
        db.returns().update(entity.copy(action = action))
    }

    /** После перезагрузки алармы не переживают выключение — ставим заново. */
    suspend fun rescheduleAll() {
        val now = Instant.now()
        db.returns().upcoming().forEach { entity ->
            val at = Instant.ofEpochMilli(entity.scheduledAt)
            scheduler.schedule(entity.id, if (at.isAfter(now)) at else now.plusSeconds(60))
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Сравниваем формулировки по смыслу: регистр и знаки роли не играют. */
    private fun normalizeText(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()

    companion object {
        /**
         * Мягкий потолок авто-разделов (scope 1.0.1 §1).
         *
         * Без него модель заводит новый раздел почти под каждую запись, и
         * структура превращается в шум — ровно то, ради чего разделы и не
         * отдавали человеку в руки.
         */
        /** По скольким буквам сличаем тему из речи с именем раздела (Р-15.13). */
        const val STEM = 4

        const val MAX_AUTO_TOPICS = 24
    }
}
