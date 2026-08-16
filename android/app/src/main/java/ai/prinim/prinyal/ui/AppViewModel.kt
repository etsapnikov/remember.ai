package ai.prinim.prinyal.ui

import ai.prinim.prinyal.PrinyalApp
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
import ai.prinim.prinyal.data.ReturnEntity
import ai.prinim.prinyal.data.Window
import ai.prinim.prinyal.domain.Backup
import ai.prinim.prinyal.domain.Scheduler
import ai.prinim.prinyal.domain.WeeklySummary
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun token(): String = app.settings.token

    fun note(id: String) = app.db.notes().watch(id)

    /** Заметки раздела; `topicId == null` — «Без раздела». */
    fun notesOf(topicId: String?) =
        if (topicId == null) app.db.notes().withoutTopic() else app.db.notes().byTopic(topicId)

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

    fun editItem(itemId: String, text: String?, type: ItemType?, window: Window?, clear: Boolean) =
        viewModelScope.launch {
            app.repository.editItem(itemId, text, type, window, clear)
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
    }

    fun showMessage(text: String?) {
        _message.value = text
    }
}
