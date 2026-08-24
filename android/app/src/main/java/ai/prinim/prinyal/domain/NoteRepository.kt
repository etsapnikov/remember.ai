package ai.prinim.prinyal.domain

import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.CaptureSource
import ai.prinim.prinyal.data.WeekRecapEntity
import ai.prinim.prinyal.data.DayEntity
import ai.prinim.prinyal.data.DueKind
import ai.prinim.prinyal.data.InterviewState
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.LinkEntity
import ai.prinim.prinyal.data.NoteEntity
import ai.prinim.prinyal.data.TopicEntity
import ai.prinim.prinyal.data.TopicKind
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.data.PersonEntity
import ai.prinim.prinyal.data.PersonNote
import ai.prinim.prinyal.data.PersonStatus
import ai.prinim.prinyal.data.SegmentEntity
import ai.prinim.prinyal.data.NoteStatus
import ai.prinim.prinyal.data.PrinyalDb
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.data.Settings
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.net.ParseResult
import ai.prinim.prinyal.returns.ReturnScheduler
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
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
    /**
     * Модель — провайдером, а не полем: клиент и репозиторий строятся лениво,
     * и жёсткая ссылка завязала бы порядок их создания узлом.
     */
    private val llm: () -> ai.prinim.prinyal.llm.DeepSeekClient? = { null },
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
    /**
     * Второй заход — с рассуждениями (Р-16.1).
     *
     * Первый разбор идёт без них: медиана 3,4 с против 14,7 с, и на всех
     * тринадцати фикстурах результат сходится — **кроме одного**: без
     * рассуждений модель не делит запись на две темы
     * (`docs/eval-reasoning.md`). Значит второй заход нужен ровно ради деления,
     * и применяем мы его только когда деление действительно нашлось.
     *
     * Пункты при этом пересобираются целиком: половины делят не строки, а
     * смысл, и пришить вторую заметку к уже разложенным пунктам нельзя. Поэтому
     * же заход отменяется, если человек успел что-то тронуть руками за эти
     * секунды: его действие важнее нашей аккуратности.
     *
     * @return true, если запись поделили
     */
    suspend fun applyDeepParse(noteId: String, result: ParseResult): Boolean {
        if (result.second == null) return false
        val note = db.notes().byId(noteId) ?: return false
        if (note.siblingId != null) return false
        val items = db.items().forNote(noteId)
        if (items.any { ItemState.of(it.state) != ItemState.PLANNED || it.edited }) {
            analytics.log(Analytics.DEEP_PARSE_SKIPPED, mapOf("note" to noteId))
            return false
        }
        applyParse(noteId, result)
        analytics.log(Analytics.DEEP_PARSE_SPLIT, mapOf("note" to noteId))
        return true
    }

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
            // Срок всей записи (Р-15.7): «верни мне это всё в понедельник».
            // Он не перебивает ни ручную дату, ни собственный срок пункта —
            // общее правило уступает частному, иначе человек, назвавший время
            // одному делу, потерял бы его из-за фразы про остальные.
            // Общий срок уступает только собственной дате пункта и правке
            // рукой. Окно он перебивает: «в понедельник» человек сказал вслух,
            // а «днём» модель предположила — и предположение, победив, увело
            // бы все три пункта на сегодня. Так и вышло, когда первый разбор
            // перестал думать: без рассуждений модель проставляет окна
            // охотнее, и правило «общий срок только поверх пустого» тихо
            // перестало срабатывать (Р-16.1).
            val shared = result.noteDueAt.takeIf {
                kept == null && parsed.dueKind != DueKind.EXACT
            }
            ItemEntity(
                id = newId(),
                noteId = noteId,
                type = parsed.type.wire,
                text = parsed.text,
                who = parsed.who,
                dueKind = when {
                    kept != null || shared != null -> DueKind.EXACT.wire
                    else -> parsed.dueKind.wire
                },
                window = if (kept != null || shared != null) null else parsed.window?.wire,
                dueAt = kept ?: shared ?: parsed.dueAt,
                state = ItemState.PLANNED.wire,
                confidence = parsed.confidence.wire,
                rawSpan = parsed.rawSpan,
                position = index,
                // Пометка переезжает вместе с датой: иначе следующий переразбор
                // сочтёт пункт нетронутым и сотрёт то, что мы только что спасли.
                edited = kept != null,
                repeatRule = parsed.repeat,
            )
        }
        db.items().insertAll(items)

        items.forEach { item -> planReturn(item, recordedAt, windows, now) }

        val topicId = resolveTopic(note, result.topic)
        rememberPeople(noteId, result.entities, result.personFacts)

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
    /**
     * Короткая запись, в которой не оказалось речи, — это касание в кармане.
     *
     * Владелец назвал их «ложными записями»: за две недели их набралось
     * девятнадцать из восьмидесяти шести, и восемнадцать он удалил руками.
     * Продукт, который просит убирать за собой чужой мусор, теряет доверие
     * быстрее, чем зарабатывает.
     *
     * Порог по длительности, а не по громкости. Пик амплитуды выглядел
     * очевидным признаком — и оказался ложным: у настоящей записи «напомни в
     * девять захватить печенье курабье» пик равен нулю, счётчик просто не
     * успевает опроситься. Отбрасывать по нему значило бы терять сказанное.
     *
     * Длинная запись без речи не удаляется: там человек говорил, а его не
     * услышали, и это уже потеря — её надо показать, а не прятать.
     *
     * @return удалили ли запись
     */
    suspend fun dropFalseTap(noteId: String): Boolean {
        val note = db.notes().byId(noteId) ?: return false
        if (note.durationMs > FALSE_TAP_MS) return false
        analytics.log("false_tap", mapOf("note" to noteId, "ms" to note.durationMs))
        runCatching { File(note.audioPath).delete() }
        db.notes().delete(noteId)
        return true
    }

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

        // Повтор проверяем до политики возвратов: у него нет срока, а политика
        // без срока не планирует ничего — и «напоминай каждый понедельник»
        // молча не напоминало бы никогда.
        // Повтор считает время сам: у него нет «срока», есть следующий раз.
        // Час берём из окна, если оно названо, иначе утро — «напоминай каждый
        // понедельник» без времени звучит как «в начале дня», а не «в полночь».
        Repeat.of(item.repeatRule)?.let { rule ->
            planRepeat(item, rule, now, windows)
            return
        }

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
     * Следующее срабатывание повтора (Р-16.2).
     *
     * Считается от [after], а не от момента записи: повтор живёт дальше своего
     * первого раза, и «каждый понедельник» после сработавшего понедельника
     * означает следующий, а не тот же самый.
     */
    private suspend fun planRepeat(
        item: ItemEntity,
        rule: Repeat,
        after: Instant,
        windows: Scheduler.Windows,
    ) {
        val time = Window.of(item.window)?.let(windows::timeOf) ?: windows.timeOf(Window.MORNING)
        val next = rule
            .next(LocalDateTime.ofInstant(after, zone), time)
            .atZone(zone).toInstant()
        check(next.isAfter(after)) { "повтор назначен в прошлое: $next" }

        val entity = ReturnEntity(
            id = newId(),
            itemId = item.id,
            scheduledAt = next.toEpochMilli(),
            attempt = 1,
        )
        db.returns().insert(entity)
        scheduler.schedule(entity.id, next)
        // Следующий раз живёт и на пункте: строка ленты обязана печататься без
        // похода в таблицу возвратов.
        db.items().update(db.items().byId(item.id)?.copy(repeatNextAt = next.toEpochMilli()) ?: return)
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
    /**
     * Круг пинг-понга дописан в тело заметки (Р-19.1).
     *
     * Дописывается **в тело**, а не в пункты: разговор об идее её растит, а не
     * порождает поручений. Вопрос печатается вместе с ответом — без него абзац
     * через неделю читается как обрывок мысли, и непонятно, почему он тут.
     *
     * Пунктов, возвратов и раздела эта запись не касается вовсе.
     */
    suspend fun appendInterviewRound(noteId: String, question: String, answer: String) {
        val note = db.notes().byId(noteId) ?: return
        val text = answer.trim()
        if (text.isEmpty()) return

        val base = note.bodyMd.orEmpty().trimEnd()
        val body = buildString {
            if (base.isNotEmpty()) append(base).append("\n\n")
            if (POLISH_HEADING !in base) append(POLISH_HEADING).append("\n\n")
            if (question.isNotBlank()) append("*").append(question.trim()).append("*").append("\n\n")
            append(text)
        }
        db.notes().update(note.copy(bodyMd = body))
        // Круг замкнулся — продукт сразу думает над следующим вопросом
        // (петля владельца от 24.08). Кнопки «Ещё вопрос» между кругами нет:
        // разговор не должен просить разрешения продолжиться.
        db.notes().setInterview(noteId, InterviewState.THINKING.wire)
    }

    /**
     * Следующий вопрос разговора (Р-21.1).
     *
     * Зовётся из фона **после** того, как ответ лёг в тело: вопрос строится по
     * всему тексту заметки, и заданный раньше повторил бы сам себя слово в
     * слово. Прошлая версия спрашивала сразу и получала тот же вопрос.
     *
     * Спросить не о чем — разговор кончается сам: пустой вопрос человеку хуже,
     * чем его отсутствие.
     */
    suspend fun askNext(noteId: String): String? {
        val note = db.notes().byId(noteId) ?: return null
        db.notes().setInterview(noteId, InterviewState.THINKING.wire)

        val idea = joinedTranscript(noteId).ifBlank { note.transcript.orEmpty() }
        val body = note.bodyMd.orEmpty()
        val asked = db.questions().forNote(noteId).map { it.text }
        val fresh = llm()?.interview(
            idea = if (body.isBlank()) idea else "$idea\n\n$body",
            asked = asked,
        )
        if (fresh == null) {
            db.notes().setInterview(noteId, InterviewState.NONE.wire)
            return null
        }
        db.questions().insert(
            ai.prinim.prinyal.data.QuestionEntity(
                id = newId(),
                noteId = noteId,
                text = fresh,
                askedAt = Instant.now().toEpochMilli(),
            )
        )
        db.notes().setInterview(noteId, InterviewState.ASKED.wire)
        analytics.log("interview_ask", mapOf("note" to noteId, "round" to asked.size + 1))
        return fresh
    }

    /**
     * Конец разговора об идее (Р-20.2, макет 13c).
     *
     * Дело — пунктом в той же заметке, с пометкой «из разговора»: она не
     * гаснет, потому что происхождение это факт, а не событие.
     *
     * Срок берётся **только из речи**: сказал «до воскресенья» — воскресенье,
     * не сказал — дело живое без даты, и продукт спросит про него в обычном
     * окне дня, как про любое другое. Правило §24.5 здесь не делает исключений
     * ради красивой концовки.
     *
     * @return созданный пункт или null, если дела не вышло
     */
    suspend fun finishInterviewWithStep(
        noteId: String,
        step: ai.prinim.prinyal.llm.DeepSeekClient.FirstStep?,
    ): ItemEntity? {
        val note = db.notes().byId(noteId) ?: return null
        db.notes().setInterview(noteId, InterviewState.NONE.wire)
        if (step == null) {
            analytics.log("interview_finish", mapOf("note" to noteId, "step" to false))
            return null
        }

        val item = ItemEntity(
            id = newId(),
            noteId = noteId,
            type = ItemType.DO.wire,
            text = step.text,
            dueKind = if (step.dueAt != null) DueKind.EXACT.wire else DueKind.NONE.wire,
            dueAt = step.dueAt,
            state = ItemState.PLANNED.wire,
            confidence = ai.prinim.prinyal.data.Confidence.HIGH.wire,
            position = db.items().forNote(noteId).size,
            fromInterview = true,
        )
        db.items().insertAll(listOf(item))
        planReturn(item, Instant.ofEpochMilli(note.createdAt), settings.windowsNow(), Instant.now())
        analytics.log(
            "interview_finish",
            mapOf("note" to noteId, "step" to true, "dated" to (step.dueAt != null)),
        )
        return item
    }

    /** Ответа не случилось (промолчал) — возвращаемся к заданному вопросу. */
    suspend fun markInterviewIdle(noteId: String) {
        db.notes().setInterview(noteId, InterviewState.ASKED.wire)
    }

    /**
     * Приписать к «Собрано» блок «Что докрутили» (Р-16.3).
     *
     * Именно приписать: прежний пересказ остаётся слово в слово. Повторный
     * «Закончить» заменяет **свой же** блок, а не громоздит второй — разговор
     * продолжается, и итог у него один.
     */
    /**
     * Был ли хоть один ответ после первого вопроса (Р-17.2).
     *
     * Живёт здесь, а не во вьюмодели, чтобы держаться тестом. Правило дорогое:
     * без него «Закончить» просил модель написать, что добавили ответы, при
     * нуле ответов — и она отвечала на собственный вопрос сама. Выдумка
     * попадала в заметку как слова человека, а отличить её там уже нечем.
     */
    suspend fun answeredAfterAsking(noteId: String): Boolean {
        val firstAsk = db.questions().forNote(noteId).minOfOrNull { it.askedAt } ?: return false
        return db.segments().forNote(noteId).any { it.createdAt > firstAsk }
    }

    suspend fun appendToBody(noteId: String, block: String): Boolean {
        val note = db.notes().byId(noteId) ?: return false
        val text = block.trim()
        if (text.isEmpty()) return false
        val base = note.bodyMd.orEmpty().substringBefore(POLISH_HEADING).trimEnd()
        val body = buildString {
            if (base.isNotEmpty()) append(base).append("\n\n")
            append(POLISH_HEADING).append("\n\n").append(text)
        }
        db.notes().update(note.copy(bodyMd = body))
        analytics.log("interview_polish", mapOf("note" to noteId, "chars" to text.length))
        return true
    }

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

    /**
     * Кого назвала эта запись (Д-26).
     *
     * Ключ имени считает [PersonIdentity], а не `lowercase()`: раньше «Юля» и
     * «Юле» заводили две строки со счётчиком 1, и порог доспроса в два
     * упоминания не брался никогда — механика молчала две недели.
     *
     * Пара человек↔запись пишется здесь же: посчитать упоминания мало, их надо
     * ещё и показать. Искать имена по тексту задним числом нельзя — «верну»
     * притворяется Верой.
     */
    private suspend fun rememberPeople(
        noteId: String,
        names: List<String>,
        facts: Map<String, String> = emptyMap(),
    ) {
        names.forEach { name ->
            val clean = name.trim()
            if (clean.isEmpty()) return@forEach
            val norm = PersonIdentity.norm(clean)
            val existing = db.people().byNorm(norm)
            val id = if (existing == null) {
                val fresh = PersonEntity(
                    id = newId(),
                    name = clean,
                    nameNorm = norm,
                    firstSeen = System.currentTimeMillis(),
                )
                db.people().insert(fresh)
                fresh.id
            } else {
                db.people().sawAgain(existing.id)
                // Склеенный живёт под именем того, в кого склеен: записи и
                // история копятся в одном месте, а не в двух.
                existing.mergedInto ?: existing.id
            }
            db.people().link(PersonNote(personId = id, noteId = noteId))

            // Что прозвучало о человеке — в карточку (Р-20.1, макет 13a).
            facts[clean]?.let { fact -> rememberFact(id, fact, noteId) }
        }
    }

    /**
     * Копим до трёх фактов о человеке (Р-20.1, макет 13a).
     *
     * Четвёртый не добавляется в хвост, а **вытесняет тот, которому
     * противоречит** («живёт в Пушкино» → «переехала в Москву»). Не
     * противоречит ничему — не берётся вовсе: три факта это знание о человеке,
     * четыре — уже досье, а записная книжка это другой продукт.
     *
     * Противоречие определяет модель: правило «переехала отменяет живёт» кодом
     * не выражается, а сравнение строк дало бы либо дубли, либо потерю знания.
     */
    private suspend fun rememberFact(personId: String, fact: String, noteId: String) {
        val text = fact.trim()
        if (text.isEmpty()) return
        val known = db.personFacts().forPerson(personId)
        // Тот же факт другими словами ловим до модели: лишний запрос на
        // каждом упоминании человека дороже, чем сравнение строк.
        if (known.any { it.text.equals(text, ignoreCase = true) }) return

        if (known.size >= MAX_FACTS) {
            val stale = llm()?.factConflict(known.map { it.text }, text) ?: return
            db.personFacts().delete(known[stale].id)
        }
        db.personFacts().insert(
            ai.prinim.prinyal.data.PersonFact(
                id = newId(),
                personId = personId,
                text = text,
                at = Instant.now().toEpochMilli(),
            )
        )
        // Статус «знаю» — чтобы человек попал в выдачу порога «две записи или
        // факт» и перестал быть кандидатом на доспрос.
        db.people().byId(personId)?.let { person ->
            if (PersonStatus.of(person.status) != PersonStatus.KNOWN) {
                db.people().update(person.copy(status = PersonStatus.KNOWN.wire, fact = text))
            } else if (person.fact != text) {
                db.people().update(person.copy(fact = text))
            }
        }
        analytics.log("person_fact", mapOf("person" to personId, "note" to noteId))
    }

    /** Три факта — знание, четыре — досье (макет 13a). */
    private val MAX_FACTS = 3

    /**
     * Разовый пересчёт людей по уже накопленным записям (Д-26).
     *
     * Без него раздел «Люди» был бы пуст ещё недели: пары человек↔запись
     * пишутся при разборе, а весь прежний корпус разобран до того, как они
     * появились.
     *
     * Имена ищутся **целым словом** ([PersonIdentity.mentions]) — по основе
     * замер давал «Вера — 13 заметок», из которых одиннадцать были словами
     * «верну» и «проверить». Здесь это не косметика: на таком сигнале раздел
     * показывал бы выдумку.
     *
     * Заодно пересчитывается ключ имени: строки, заведённые до нормализации,
     * иначе так и остались бы порознь.
     *
     * @return сколько пар записано
     */
    suspend fun backfillPeople(): Int {
        val people = db.people().allLive()
        if (people.isEmpty()) return 0
        val notes = db.notes().all().filter { !it.transcript.isNullOrBlank() }

        // Сначала ключи: без них однофамильцы в разных падежах останутся
        // разными строками, и пересчёт закрепит старую ошибку.
        val byNorm = mutableMapOf<String, PersonEntity>()
        people.forEach { person ->
            val norm = PersonIdentity.norm(person.name)
            val canonical = byNorm[norm]
            if (canonical == null) {
                byNorm[norm] = person
                if (person.nameNorm != norm) db.people().update(person.copy(nameNorm = norm))
            } else {
                // Тот же ключ — та же строка, просто заведённая дважды. Это не
                // вопрос к человеку: «Юля» и «Юле» одно имя, а не два лица.
                db.people().mergeInto(person.id, canonical.id)
            }
        }

        var pairs = 0
        byNorm.values.forEach { person ->
            notes.forEach { note ->
                if (PersonIdentity.mentions(note.transcript.orEmpty(), person.name)) {
                    db.people().link(PersonNote(personId = person.id, noteId = note.id))
                    pairs++
                }
            }
        }
        analytics.log("people_backfill", mapOf("people" to byNorm.size, "pairs" to pairs))
        return pairs
    }

    /**
     * Ответ на вопрос о склейке (Д-30): «один» — записи и история съезжаются в
     * одного человека; «разные» — фиксируем навсегда, чтобы не переспрашивать.
     */
    suspend fun mergePeople(fromId: String, intoId: String) {
        db.people().mergeInto(fromId, intoId)
        analytics.log("people_merged", mapOf("from" to fromId, "into" to intoId))
    }

    /** «Разные» (Д-30): фиксируем навсегда, чтобы не переспрашивать. */
    suspend fun keepPeopleApart(a: String, b: String) {
        db.people().keepApart(a, b)
        db.people().keepApart(b, a)
        analytics.log("people_apart", mapOf("a" to a, "b" to b))
    }

    /**
     * Прогнать словарь по уже распознанным транскриптам.
     *
     * Вызывается при заведении правила: человек поправил слово, глядя на
     * конкретную запись, и ожидает увидеть исправление там же. Аудио при этом
     * не трогается — оно и есть настоящая запись; меняется только машинная
     * расшифровка, которую человек и правит.
     *
     * @return скольких заметок это коснулось
     */
    suspend fun applyRuleToTranscripts(): Int {
        val rules = db.replacements().all()
        if (rules.isEmpty()) return 0
        var touched = 0
        db.notes().all().forEach { note ->
            val heard = note.transcript ?: return@forEach
            if (heard.isBlank()) return@forEach
            val applied = Replacements.apply(heard, rules)
            if (applied.text == heard) return@forEach
            db.notes().update(note.copy(transcript = applied.text))
            applied.hits.forEach { (id, times) -> db.replacements().addHits(id, times) }
            touched++
        }
        return touched
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
            // Названная руками дата отменяет повтор. Иначе получилось бы тихое
            // противоречие: человек поставил двадцать пятое, экран показывает
            // «каждый понедельник», приходит понедельник — и объяснить это
            // нечем. Одно расписание на пункт, и последнее слово за человеком.
            repeatRule = if (clearSchedule || exactAt != null) null else item.repeatRule,
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

    /**
     * «Сделано».
     *
     * У повторяющегося пункта это значит не то же, что у обычного: он закрылся
     * **до следующего раза**, а не насовсем. Поэтому повтор уходит не в
     * `done`, а в `returned` с новой датой — состояние в продукте уже есть и
     * означает буквально это (макеты 1.0.4, блок 10b). Зелёное «сделано» и
     * слово «закрыто» остаются означать «насовсем», и повтор в сводке
     * «5 сделано» не появляется никогда.
     *
     * @return момент следующего раза, если пункт повторяется
     */
    suspend fun markDone(itemId: String): Instant? {
        val item = db.items().byId(itemId)
        val rule = Repeat.of(item?.repeatRule)
        if (item != null && rule != null) {
            db.items().setState(itemId, ItemState.RETURNED.wire)
            // Прежний назначенный раз снимаем: человек ответил раньше звонка,
            // и звонить всё равно было бы враньём про «сделал».
            db.returns().forItem(itemId).filter { it.firedAt == null }
                .forEach { scheduler.cancel(it.id) }
            db.returns().dropPending(itemId)

            // Раз записывается в те же возвраты: история повтора («18 авг
            // сделал · 11 авг не ответил») — это и есть список его
            // срабатываний, и заводить под неё вторую таблицу значило бы
            // держать два счёта одного и того же.
            val now = Instant.now()
            val fired = db.returns().forItem(itemId)
                .filter { it.firedAt != null && it.action == null }
                .maxByOrNull { it.firedAt!! }
            if (fired != null) {
                db.returns().update(fired.copy(action = ACTION_DONE))
            } else {
                // Закрыли раньше звонка — раз всё равно был, и в истории он
                // обязан остаться, иначе «всего 9 раз» соврёт.
                db.returns().insert(
                    ReturnEntity(
                        id = newId(),
                        itemId = itemId,
                        scheduledAt = now.toEpochMilli(),
                        firedAt = now.toEpochMilli(),
                        action = ACTION_DONE,
                    )
                )
            }
            db.items().update(
                db.items().byId(itemId)!!.copy(repeatDoneAt = now.toEpochMilli())
            )
            planRepeat(item, rule, now, settings.windowsNow())
            analytics.log(
                Analytics.RETURN_ACTION,
                mapOf("item" to itemId, "action" to "done", "repeat" to item.repeatRule),
            )
            return db.returns().forItem(itemId)
                .filter { it.firedAt == null }
                .minByOrNull { it.scheduledAt }
                ?.let { Instant.ofEpochMilli(it.scheduledAt) }
        }
        setStateAndStop(itemId, ItemState.DONE)
        analytics.log(Analytics.RETURN_ACTION, mapOf("item" to itemId, "action" to "done"))
        refreshWidgetCount()
        return null
    }

    /**
     * Счёт закрытого за неделю — для строки на виджете (Р-20.3, макет 13d).
     *
     * Кладём в prefs, а не считаем в виджете: `onUpdate` живёт миллисекунды, и
     * запрос к базе оттуда — способ получить пустую строку на медленном
     * телефоне. Цифра меняется только когда человек что-то закрыл.
     */
    private suspend fun refreshWidgetCount() {
        val week = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli()
        val closed = db.items().all().count {
            ItemState.of(it.state) == ItemState.DONE &&
                (db.notes().byId(it.noteId)?.createdAt ?: 0) >= week
        }
        settings.setClosedThisWeek(closed)
    }

    /**
     * Заголовок блока пинг-понга. Он же граница: по нему прежний блок находят
     * и заменяют, поэтому менять его текст — значит оставить в базе сироту.
     */
    private val POLISH_HEADING = "## Что докрутили"

    /** Метка сделанного раза в истории повтора. */
    private val ACTION_DONE = "done"

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

    // --- дни (Р-18) ---

    /**
     * Сохранить вечерний ответ (Р-18.1).
     *
     * REPLACE по дате: второго ответа за вечер не бывает, но если человек
     * наговорил снова (открыл уведомление дважды), новый ответ — правда,
     * а не дубль.
     */
    suspend fun saveDay(date: String, audio: File, durationMs: Long, createdAt: Instant) {
        db.days().insert(
            DayEntity(
                date = date,
                audioPath = audio.absolutePath,
                durationMs = durationMs,
                createdAt = createdAt.toEpochMilli(),
            )
        )
    }

    /**
     * Итог недели (Р-18.3): собрать один раз, в воскресенье вечером.
     *
     * Меньше [WEEK_RECAP_MIN_DAYS] отвеченных дней — итога нет и уведомления
     * нет: сводка из двух вечеров — это пересказ двух вечеров, а не неделя.
     * Уже собран — не пересобирается: понедельничный взгляд не должен менять
     * воскресную память.
     *
     * @return текст итога, если он собрался сейчас
     */
    suspend fun buildWeekRecap(today: LocalDate = LocalDate.now()): String? {
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        if (db.weekRecaps().byWeek(monday.toString()) != null) return null

        val days = db.days().between(monday.toString(), monday.plusDays(6).toString())
            .filter { !it.transcript.isNullOrBlank() }
        if (days.size < WEEK_RECAP_MIN_DAYS) return null

        // Люди — в контекст сводки (Р-19.3): «Игорь» в ответе про смету и
        // «Игорь — подрядчик по даче» из карточки — один человек, и сводка,
        // которая этого не знает, пишет про двух разных.
        val people = db.people().known().map { "${it.name} — ${it.fact}" }
        val text = llm()?.weekRecap(days.map { it.date to it.transcript.orEmpty() }, people)
            ?: return null
        db.weekRecaps().insert(
            WeekRecapEntity(
                weekStart = monday.toString(),
                text = text,
                createdAt = Instant.now().toEpochMilli(),
            )
        )
        analytics.log("week_recap", mapOf("week" to monday.toString(), "days" to days.size))
        return text
    }

    /**
     * «В план» (Р-18.4, макеты 11d–11e): закрытый пункт снова живой.
     *
     * Граница правила «закрытое неприкосновенно» проведена дизайнером:
     * неприкосновенны **слова** — текст, источник в речи и история не меняются
     * никогда. Состояние — не слово: закрыл сам, сам и передумал.
     *
     * Возвращается **без срока**: прежний срок в прошлом, а ближайшее окно
     * продукт выдумал бы сам — расписание в этом продукте назначает только
     * речь. Возврат — не стирание, а событие: «сделал» остаётся в истории,
     * под ним встаёт «вернул в план».
     *
     * @return прежнее состояние — для отката из снекбара
     */
    suspend fun reviveItem(itemId: String): ItemState? {
        val item = db.items().byId(itemId) ?: return null
        val state = ItemState.of(item.state)
        if (state !in setOf(ItemState.DONE, ItemState.DISMISSED, ItemState.EXPIRED)) return null
        // Повتору «В план» не нужен: сделанный повтор и так уходит в «вернусь».
        if (item.repeatRule != null) return null

        db.items().update(
            item.copy(
                state = ItemState.PLANNED.wire,
                dueKind = DueKind.NONE.wire,
                window = null,
                dueAt = null,
                revivedAt = Instant.now().toEpochMilli(),
            )
        )
        analytics.log("item_revived", mapOf("item" to itemId, "from" to state.wire))
        return state
    }

    /** Откат «В план» из снекбара: пункт закрывается обратно тем же словом. */
    suspend fun unreviveItem(itemId: String, back: ItemState) {
        val item = db.items().byId(itemId) ?: return
        db.items().update(item.copy(state = back.wire, revivedAt = null))
    }

    /** Меньше трёх дней — не неделя (решение дизайнера, 12e). */
    private val WEEK_RECAP_MIN_DAYS = 3

    // --- возвраты ---

    /**
     * Второй заход по проигнорированному возврату. Третьего нет: дальше `expired`,
     * который ждёт R2-разбора, а не пилит пользователя (F-6).
     */
    suspend fun scheduleSecondAttempt(returnId: String) {
        val fired = db.returns().byId(returnId) ?: return
        // Повторяющийся пункт второго захода не получает и не «протухает»:
        // следующий раз ему уже назначен, а двойное напоминание об одном и том
        // же понедельнике — это долбёж, от которого повтор и должен избавить.
        if (db.items().byId(fired.itemId)?.repeatRule != null) return
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

    /**
     * «Не повторять».
     *
     * Не удаляет пункт и не отменяет ближайший раз: превращает вечное дело в
     * обычное, назначенное на тот день, который и так был следующим (макеты
     * 10c). Поэтому назначенный возврат остаётся жить — снимать и ставить
     * заново значило бы сдвинуть время из-за смены окна.
     *
     * @return момент, на который пункт остался, или null, если повтора не было
     */
    suspend fun stopRepeat(itemId: String): Instant? {
        val item = db.items().byId(itemId) ?: return null
        if (item.repeatRule == null) return null

        val kept = db.returns().forItem(itemId)
            .filter { it.firedAt == null }
            .minByOrNull { it.scheduledAt }
            ?.let { Instant.ofEpochMilli(it.scheduledAt) }
            ?: item.repeatNextAt?.let(Instant::ofEpochMilli)

        db.items().update(
            item.copy(
                repeatRule = null,
                repeatDoneAt = null,
                repeatNextAt = null,
                // Пункт становится обычным делом с названной датой. Без этого
                // он остался бы «просто сохраню» — и назначенный возврат
                // выглядел бы взявшимся ниоткуда.
                dueKind = if (kept != null) DueKind.EXACT.wire else item.dueKind,
                dueAt = kept?.toEpochMilli() ?: item.dueAt,
                window = if (kept != null) null else item.window,
                state = ItemState.PLANNED.wire,
            )
        )
        analytics.log("repeat_off", mapOf("item" to itemId))
        return kept
    }

    /** Откат из снекбара: правило возвращается, ближайший раз остаётся тем же. */
    suspend fun resumeRepeat(itemId: String, rule: String) {
        val item = db.items().byId(itemId) ?: return
        val next = db.returns().forItem(itemId)
            .filter { it.firedAt == null }
            .minByOrNull { it.scheduledAt }
            ?.scheduledAt
        db.items().update(
            item.copy(
                repeatRule = rule,
                repeatNextAt = next,
                dueKind = DueKind.NONE.wire,
                dueAt = null,
            )
        )
        if (next == null) {
            Repeat.of(rule)?.let { planRepeat(item, it, Instant.now(), settings.windowsNow()) }
        }
        analytics.log("repeat_off_undo", mapOf("item" to itemId))
    }

    suspend fun markFired(returnId: String) {
        val entity = db.returns().byId(returnId) ?: return
        db.returns().update(entity.copy(firedAt = Instant.now().toEpochMilli()))
        db.items().setState(entity.itemId, ItemState.RETURNED.wire)

        // Повторяющийся пункт сразу получает следующий раз — здесь, а не после
        // ответа: человек может не ответить вовсе, и повтор, который живёт
        // только до первого молчания, бесполезен именно в тех случаях, ради
        // которых его и завели.
        db.items().byId(entity.itemId)?.let { item ->
            Repeat.of(item.repeatRule)?.let { rule ->
                // Новый раз — новая возможность его сделать: метку прошлого
                // снимаем, иначе «сделано» осталось бы спрятанным навсегда.
                db.items().update(item.copy(repeatDoneAt = null))
                // Отсчёт от **назначенного** момента, а не от фактического:
                // аларм может прозвонить с опозданием — телефон спал, процесс
                // был убит, — и «через неделю после звонка» медленно уводило бы
                // напоминание с понедельника на вторник и дальше. Позже
                // назначенного берём текущий момент, иначе после долгого сна
                // получится возврат в прошлое.
                val from = maxOf(Instant.ofEpochMilli(entity.scheduledAt), Instant.now())
                planRepeat(item, rule, from, settings.windowsNow())
            }
        }
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

    /**
     * После перезагрузки алармы не переживают выключение — ставим заново.
     *
     * Заодно лечим возвраты, назначенные в невозможное будущее. Такие остались
     * от сборки 1.0.1, где точная дата считалась в секундах, а расписание
     * читало их как миллисекунды: пункт с датой уезжал в 58601 год и не
     * приходил никогда — молча, потому что возврат в базе есть и выглядит
     * запланированным.
     *
     * Чинить их надо здесь, а не миграцией: миграция знает только строки, а
     * правильное время считается от окон и часового пояса, которые живут в
     * настройках.
     */
    suspend fun rescheduleAll() {
        val now = Instant.now()
        val horizon = now.plus(MAX_HORIZON_DAYS, ChronoUnit.DAYS)
        val windows = settings.windowsNow()

        db.returns().upcoming().forEach { entity ->
            var at = Instant.ofEpochMilli(entity.scheduledAt)

            if (at.isAfter(horizon)) {
                val item = db.items().byId(entity.itemId)
                val fromItem = item?.dueAt
                    ?.let(Instant::ofEpochMilli)
                    ?.takeIf { it.isAfter(now) && !it.isAfter(horizon) }
                at = fromItem ?: Scheduler.nextWindowAfter(now, windows, zone)
                db.returns().update(entity.copy(scheduledAt = at.toEpochMilli()))
                analytics.log(
                    "return_repaired",
                    mapOf("return" to entity.id, "was" to entity.scheduledAt),
                )
            }

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

        /**
         * Дальше этого горизонта возврат не бывает настоящим: столько человек
         * не планирует, а вот ошибка в единицах времени даёт ровно такие даты.
         */
        const val MAX_HORIZON_DAYS = 400L

        /**
         * До этой длительности запись без единого распознанного слова считается
         * случайным касанием. Пять секунд: короче человек не успевает сказать
         * даже «напомни завтра позвонить», а карман нажимает именно так.
         */
        const val FALSE_TAP_MS = 5_000L
    }
}
