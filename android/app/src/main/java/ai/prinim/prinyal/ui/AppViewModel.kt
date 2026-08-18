package ai.prinim.prinyal.ui

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.R
import ai.prinim.prinyal.capture.SilenceWindow
import ai.prinim.prinyal.capture.UploadWorker
import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteWithItems
import ai.prinim.prinyal.data.TopicEntity
import ai.prinim.prinyal.data.TopicKind
import ai.prinim.prinyal.data.TopicOverview
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.data.ReplacementEntity
import ai.prinim.prinyal.domain.Replacements
import ai.prinim.prinyal.domain.StructureRepair
import ai.prinim.prinyal.data.PersonEntity
import ai.prinim.prinyal.data.PersonStatus
import ai.prinim.prinyal.domain.AskPolicy
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Backup
import ai.prinim.prinyal.domain.Scheduler
import ai.prinim.prinyal.domain.WeekSignal
import ai.prinim.prinyal.domain.WeeklyFacts
import ai.prinim.prinyal.domain.WeeklySummary
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = PrinyalApp.of(application)

    val feed: StateFlow<List<NoteWithItems>> = app.db.notes().feed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val topics: StateFlow<List<TopicOverview>> = app.db.topics().overview()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val looseNotes: StateFlow<Int> = app.db.topics().looseCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val replacements: StateFlow<List<ReplacementEntity>> = app.db.replacements().watch()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Кого спросить в ленте. Карточка одна на весь продукт: пока висит эта,
     * следующего вопроса не появится, даже если непонятных имён накопилось три.
     */
    val askCandidate: StateFlow<PersonEntity?> = app.db.people().candidate()
        .map { person ->
            if (AskPolicy.canAsk(person, app.db.people().lastAskedAt(), System.currentTimeMillis())) {
                person
            } else {
                null
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pendingCount: StateFlow<Int> = app.db.notes().pendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val serverUrl: StateFlow<String> = app.settings.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val llmEnabled: StateFlow<Boolean> = app.settings.llmEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val windows: StateFlow<Scheduler.Windows> = app.settings.windows
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Scheduler.Windows())

    val silenceThreshold: StateFlow<Int> = app.settings.silenceThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 900)

    val silencePatience: StateFlow<SilenceWindow.Patience> = app.settings.silencePatience
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SilenceWindow.Patience.NORMAL,
        )

    private val _health = MutableStateFlow<Boolean?>(null)
    val health: StateFlow<Boolean?> = _health

    private val _weekly = MutableStateFlow<WeeklySummary.Report?>(null)
    val weekly: StateFlow<WeeklySummary.Report?> = _weekly

    /** Предложение починить структуру (Р-15.12). null — продукт молчит. */
    private val _structure = MutableStateFlow<StructureRepair.Offer?>(null)
    val structure: StateFlow<StructureRepair.Offer?> = _structure

    /** Наблюдения недели (Р-15.9). Пусто — значит рассказывать нечего. */
    private val _weeklyFacts = MutableStateFlow<List<WeeklyFacts.Fact>>(emptyList())
    val weeklyFacts: StateFlow<List<WeeklyFacts.Fact>> = _weeklyFacts

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    companion object {
        /** Псевдо-раздел решений: собирается запросом, топиком не является. */
        const val DECISIONS = "@decisions"
    }

    fun token(): String = app.settings.token

    fun note(id: String) = app.db.notes().watch(id)

    /** Связи заметки — обе стороны сразу (Р-15.11). */
    fun linked(id: String) = app.db.links().forNote(id)

    /**
     * Заметки раздела; `topicId == null` — «Без раздела», [DECISIONS] — решения.
     *
     * Сентинел, а не топик: раздел решений собирается запросом (Р-15.10).
     * Настоящие id — uuid, поэтому «@» в имени столкновение исключает.
     */
    fun notesOf(topicId: String?) = when (topicId) {
        null -> app.db.notes().withoutTopic()
        DECISIONS -> app.db.notes().decisions()
        else -> app.db.notes().byTopic(topicId)
    }

    /** Сколько решений накопилось — от этого зависит, есть ли строка в списке. */
    val decisionCount = app.db.notes().decisionCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    suspend fun liveTopics(): List<TopicEntity> = app.db.topics().live()

    /**
     * Завести правило словаря.
     *
     * К уже сохранённым транскриптам оно не применяется: транскрипт — это то,
     * что было услышано тогда, и переписывать прошлое продукт не вправе
     * (Д-2). Правило начнёт работать со следующей записи и с ближайшего
     * переразбора этой.
     */
    fun addReplacement(from: String, to: String) = viewModelScope.launch {
        if (!Replacements.fits(from) || to.isBlank()) return@launch
        val norm = Replacements.norm(from)
        app.db.replacements().insert(
            ReplacementEntity(
                id = app.db.replacements().all().firstOrNull { it.fromNorm == norm }?.id
                    ?: app.repository.newId(),
                fromPhrase = from.trim(),
                fromNorm = norm,
                toPhrase = to.trim(),
                createdAt = System.currentTimeMillis(),
            )
        )
        app.analytics.log(Analytics.REPLACEMENT_ADD, mapOf("from" to from, "to" to to))
    }

    fun removeReplacement(id: String) = viewModelScope.launch {
        val rule = app.db.replacements().byId(id) ?: return@launch
        app.db.replacements().delete(id)
        _undo.value = UndoEvent(UndoMessage.RuleRemoved) {
            app.db.replacements().insert(rule)
        }
    }

    /**
     * Разложить по разделам заметки, сделанные до 1.0.1.
     *
     * Идёт по одной и вслух: каждая запись — отдельный вызов модели с
     * рассуждениями, это секунды и деньги. Молчаливый фоновый прогон на два
     * десятка записей выглядел бы как зависшее приложение.
     */
    fun retroClassify() = viewModelScope.launch(Dispatchers.IO) {
        // Dispatchers.IO обязателен: `llm.parse` — синхронный сетевой вызов, а
        // viewModelScope по умолчанию живёт на главном потоке. Из-за этого
        // кнопка выглядела мёртвой: корутина падала с
        // NetworkOnMainThreadException в первый же заход, молча.
        _retro.value = RetroState(0, 0, running = true)
        val notes = app.db.notes().looseList()
        if (notes.isEmpty()) {
            _retro.value = RetroState(0, 0, running = false)
            return@launch
        }
        var done = 0
        notes.forEachIndexed { index, note ->
            _retro.value = RetroState(index + 1, notes.size, running = true)
            val outcome = app.llm.parse(
                transcript = note.transcript.orEmpty(),
                now = java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(note.createdAt),
                    java.time.ZoneId.systemDefault(),
                ),
                zone = java.time.ZoneId.systemDefault(),
                topics = app.db.topics().live().map { it.name },
                glossary = Replacements.glossary(app.db.replacements().all()),
            )
            if (outcome is ai.prinim.prinyal.net.IngestOutcome.Ok) {
                if (app.repository.applyTopicOnly(note.id, outcome.result)) done++
            }
        }
        _retro.value = RetroState(done, notes.size, running = false)
    }

    /** Ход раскладки: без него кнопка молчит и выглядит сломанной. */
    data class RetroState(val done: Int, val total: Int, val running: Boolean)

    private val _retro = MutableStateFlow<RetroState?>(null)
    val retro: StateFlow<RetroState?> = _retro

    /** Сколько заметок ждут раскладки — показываем до запуска, вместе с ценой. */
    suspend fun looseCountNow(): Int = app.db.notes().looseList().size

    /** «Не надо» — закрывает имя навсегда и молча. Никаких «вы уверены». */
    fun declinePerson(id: String) = viewModelScope.launch {
        val person = app.db.people().byId(id) ?: return@launch
        app.db.people().update(
            person.copy(
                status = PersonStatus.DECLINED.wire,
                askedAt = System.currentTimeMillis(),
            )
        )
        app.analytics.log(Analytics.ENTITY_DECLINE, mapOf("name" to person.name))
    }

    /** Ответ человека: одно слово в meta-строке пунктов, где он упомянут. */
    fun answerPerson(id: String, fact: String) = viewModelScope.launch {
        val person = app.db.people().byId(id) ?: return@launch
        if (fact.isBlank()) return@launch
        app.db.people().update(
            person.copy(
                status = PersonStatus.KNOWN.wire,
                fact = fact.trim(),
                askedAt = System.currentTimeMillis(),
            )
        )
        app.analytics.log(Analytics.ENTITY_ANSWER, mapOf("name" to person.name))
    }

    /** Вопрос показан — отсчёт трёх дней идёт с этого момента. */
    fun markAsked(id: String) = viewModelScope.launch {
        val person = app.db.people().byId(id) ?: return@launch
        if (person.askedAt != null) return@launch
        app.db.people().update(person.copy(askedAt = System.currentTimeMillis()))
        app.analytics.log(Analytics.ENTITY_ASK, mapOf("name" to person.name))
    }

    /** Склеить половины разделённой записи обратно (Р-15.5). */
    fun mergeSiblings(noteId: String) = viewModelScope.launch {
        app.repository.mergeSiblings(noteId)
    }

    suspend fun topicName(id: String?): String? =
        id?.let { app.db.topics().byId(it)?.name }

    /**
     * Отнести заметку к разделу рукой.
     *
     * Ручной выбор замораживает топик: последующие переразборы его не
     * перезаписывают. Иначе правка руками стала бы вечной — человек относит,
     * машина возвращает обратно (scope 1.0.1 §0 п.4).
     */
    fun setTopic(noteId: String, topicId: String?) = viewModelScope.launch {
        app.db.notes().setTopic(noteId, topicId, TopicSource.USER.wire)
        app.analytics.log(
            Analytics.TOPIC_EDITED,
            mapOf("note" to noteId, "topic" to topicId),
        )
    }

    /** Создать раздел вместе с отнесением: «создать впрок» в продукте нет. */
    fun createTopicAndAssign(noteId: String, name: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        val norm = clean.lowercase().replace(Regex("\\s+"), " ")
        val existing = app.db.topics().byNorm(norm)
        val id = existing?.id ?: app.repository.newId().also {
            app.db.topics().insert(
                TopicEntity(
                    id = it,
                    name = clean,
                    nameNorm = norm,
                    kind = TopicKind.MANUAL.wire,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        app.db.notes().setTopic(noteId, app.db.topics().byNorm(norm)?.id ?: id, TopicSource.USER.wire)
        app.analytics.log(Analytics.TOPIC_EDITED, mapOf("note" to noteId, "topic" to clean, "new" to true))
    }

    suspend fun returnsFor(itemId: String): List<ReturnEntity> =
        app.db.returns().forItem(itemId)

    // --- действия над айтемом ---

    fun markDone(itemId: String) = viewModelScope.launch { app.repository.markDone(itemId) }

    fun dismiss(itemId: String) = viewModelScope.launch { app.repository.dismissItem(itemId) }

    fun bury(itemId: String) = viewModelScope.launch { app.repository.buryItem(itemId) }

    fun editItem(
        itemId: String,
        text: String?,
        type: ItemType?,
        window: Window?,
        exactAt: Long? = null,
        clear: Boolean,
    ) = viewModelScope.launch {
        app.repository.editItem(itemId, text, type, window, exactAt, clear)
    }

    /**
     * Сохранить правленый транскрипт и переразобрать (спека R1.2 §15).
     *
     * ASR второй раз не гоняем: воркер пропускает распознавание, если транскрипт
     * уже есть, — то есть в разбор уйдёт именно правленый текст.
     */
    fun saveTranscriptAndReparse(noteId: String, transcript: String) = viewModelScope.launch {
        app.repository.replaceTranscript(noteId, transcript)
        UploadWorker.enqueue(getApplication(), noteId)
    }

    /** Правка отброшена — снекбар с «вернуть» вернёт человека в редактор. */
    fun showDroppedEdit() {
        _undo.value = UndoEvent(UndoMessage.EditDropped) { }
    }

    /** Перезапуск разбора после ошибки: аудио цело, значит шанс есть (F-7). */
    fun reparse(noteId: String) = viewModelScope.launch {
        app.db.notes().setStatus(noteId, ai.prinim.prinyal.data.NoteStatus.RECORDED.wire)
        UploadWorker.enqueue(getApplication(), noteId)
    }

    // --- удаление с undo (спека R1.1 §2.2) ---

    /** Что показывает снекбар и что сделает «вернуть». */
    data class UndoEvent(val message: UndoMessage, val undo: suspend () -> Unit)

    sealed interface UndoMessage {
        data object NoteDeleted : UndoMessage
        data class JunkSwept(val count: Int) : UndoMessage
        data object ItemBuried : UndoMessage
        data object EditDropped : UndoMessage
        data object RuleRemoved : UndoMessage
    }

    private val _undo = MutableStateFlow<UndoEvent?>(null)
    val undo: StateFlow<UndoEvent?> = _undo

    fun consumeUndo() {
        _undo.value = null
    }

    fun deleteNote(noteId: String) = viewModelScope.launch {
        app.repository.softDeleteNote(noteId)
        _undo.value = UndoEvent(UndoMessage.NoteDeleted) {
            app.repository.restoreNote(noteId)
        }
    }

    fun sweepJunk() = viewModelScope.launch {
        val swept = app.repository.sweepJunk()
        if (swept.isEmpty()) return@launch
        _undo.value = UndoEvent(UndoMessage.JunkSwept(swept.size)) {
            swept.forEach { app.repository.restoreNote(it) }
        }
    }

    fun buryWithUndo(itemId: String) = viewModelScope.launch {
        app.repository.buryItem(itemId)
        _undo.value = UndoEvent(UndoMessage.ItemBuried) {
            app.repository.unburyItem(itemId)
        }
    }

    /** Снекбар истёк или экран покинут — точка невозврата. */
    fun purgeDeleted() = viewModelScope.launch { app.repository.purgeDeleted() }

    fun runUndo(event: UndoEvent) = viewModelScope.launch { event.undo() }

    // --- настройки ---

    fun setServerUrl(value: String) = viewModelScope.launch { app.settings.setServerUrl(value) }

    fun setToken(value: String) {
        app.settings.token = value
    }

    fun setLlmEnabled(value: Boolean) = viewModelScope.launch { app.settings.setLlmEnabled(value) }

    fun setWindow(window: Window, time: LocalTime) =
        viewModelScope.launch { app.settings.setWindow(window, time) }

    /**
     * Установка окна с валидацией (спека R1.1 §5): окна дня не пересекаются.
     * При конфликте соседнее сдвигается на 30 минут, о сдвиге сообщает снекбар
     * «Сдвинул вечер на 20:00». Выходные — отдельный день, с буднями не конфликтуют.
     */
    fun setWindowValidated(window: Window, picked: LocalTime) = viewModelScope.launch {
        app.settings.setWindow(window, picked)
        if (window == Window.WEEKEND) return@launch

        val names = mapOf(
            Window.MORNING to "утро",
            Window.DAY to "день",
            Window.EVENING to "вечер",
        )
        var shifted: Pair<Window, LocalTime>? = null

        var current = app.settings.windowsNow()
        // Каскад вперёд: morning < day < evening, шаг между соседями минимум 30 минут.
        if (current.day <= current.morning) {
            val moved = current.morning.plusMinutes(30)
            app.settings.setWindow(Window.DAY, moved)
            if (window != Window.DAY) shifted = shifted ?: (Window.DAY to moved)
        }
        current = app.settings.windowsNow()
        if (current.evening <= current.day) {
            val moved = current.day.plusMinutes(30)
            app.settings.setWindow(Window.EVENING, moved)
            if (window != Window.EVENING) shifted = shifted ?: (Window.EVENING to moved)
        }
        // Каскад назад: выбрали день раньше утра — утро уезжает вниз.
        current = app.settings.windowsNow()
        if (current.morning >= current.day) {
            val moved = current.day.minusMinutes(30)
            app.settings.setWindow(Window.MORNING, moved)
            if (window != Window.MORNING) shifted = shifted ?: (Window.MORNING to moved)
        }

        shifted?.let { (which, at) ->
            showMessage(
                getApplication<Application>().getString(
                    ai.prinim.prinyal.R.string.settings_window_shifted,
                    names[which],
                    "%02d:%02d".format(at.hour, at.minute),
                )
            )
        }
    }

    fun setSilenceThreshold(value: Int) =
        viewModelScope.launch { app.settings.setSilenceThreshold(value) }

    fun setSilencePatience(value: SilenceWindow.Patience) =
        viewModelScope.launch { app.settings.setSilencePatience(value) }

    fun checkHealth() = viewModelScope.launch {
        _health.value = null
        val url = app.settings.serverUrlNow()
        val token = app.settings.token
        _health.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.api.health(url, token)
        }
    }

    /** Страховка dogfood-данных: вся база одним файлом (F-8). */
    fun export(onDone: (File) -> Unit) = viewModelScope.launch {
        val file = Backup(getApplication(), app.db, app.analytics).export()
        onDone(file)
    }

    fun import(file: File, onDone: (Int) -> Unit) = viewModelScope.launch {
        val count = Backup(getApplication(), app.db, app.analytics).import(file)
        app.repository.rescheduleAll()
        onDone(count)
    }

    fun loadWeekly() = viewModelScope.launch {
        _weekly.value = WeeklySummary(app.analytics, app.db).build()
        _weeklyFacts.value = WeeklyFacts.facts(WeekSignal(app.db).build())
        _structure.value = findStructureOffer()
    }

    /**
     * Что предложить по структуре — или ничего.
     *
     * Такт проверяется **до** поиска: иначе находка начинает оправдывать
     * нарушение тишины, и продукт превращается в того, кто требует навести
     * порядок (Р-15.12).
     */
    private suspend fun findStructureOffer(): StructureRepair.Offer? {
        val now = System.currentTimeMillis()
        if (!StructureRepair.maySpeak(app.settings.lastStructureOffer(), now)) return null

        val notes = app.db.notes().all().filter { !it.transcript.isNullOrBlank() }
        val byTopic = notes.filter { it.topicId != null }.groupBy { it.topicId!! }
        val names = app.db.topics().live().associate { it.id to it.name }

        val refused = buildSet {
            byTopic.keys.forEach { id ->
                if (StructureRepair.refusalHolds(app.settings.structureRefusedAt(id), now)) add(id)
            }
            if (StructureRepair.refusalHolds(
                    app.settings.structureRefusedAt(StructureRepair.ORPHANS_KEY), now,
                )
            ) {
                add(StructureRepair.ORPHANS_KEY)
            }
        }

        fun repairNotes(list: List<ai.prinim.prinyal.data.NoteEntity>) =
            list.map { StructureRepair.Note(it.id, it.transcript.orEmpty()) }

        return StructureRepair.offer(
            bigTopics = byTopic.mapNotNull { (id, list) ->
                names[id]?.let { StructureRepair.Topic(id, it) to repairNotes(list) }
            },
            orphans = repairNotes(notes.filter { it.topicId == null }),
            linked = app.db.links().pairs().map { it.fromNoteId to it.toNoteId }.toSet(),
            refused = refused,
        )
    }

    /** «Да»: заметки кластера переезжают в новый раздел, названный его словом. */
    fun acceptStructure(name: String) = viewModelScope.launch {
        val offer = _structure.value ?: return@launch
        val ids = when (offer) {
            is StructureRepair.Offer.Split -> offer.noteIds
            is StructureRepair.Offer.Gather -> offer.noteIds
        }
        app.repository.moveToNewTopic(ids, name)
        app.settings.structureOffered(System.currentTimeMillis())
        _structure.value = null
    }

    /** «Не надо»: тема закрывается на месяц, а не до перезапуска. */
    fun refuseStructure() = viewModelScope.launch {
        val offer = _structure.value ?: return@launch
        val key = when (offer) {
            is StructureRepair.Offer.Split -> offer.topicId
            is StructureRepair.Offer.Gather -> StructureRepair.ORPHANS_KEY
        }
        val now = System.currentTimeMillis()
        app.settings.structureRefused(key, now)
        app.settings.structureOffered(now)
        _structure.value = null
    }

    fun showMessage(text: String?) {
        _message.value = text
    }
}
