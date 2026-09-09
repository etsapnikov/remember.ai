package ai.prinim.prinyal.ui

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.R
import ai.prinim.prinyal.capture.SilenceWindow
import ai.prinim.prinyal.capture.UploadWorker
import ai.prinim.prinyal.data.Analytics
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.ItemType
import ai.prinim.prinyal.data.NoteWithItems
import ai.prinim.prinyal.data.TopicEntity
import ai.prinim.prinyal.data.TopicKind
import ai.prinim.prinyal.data.TopicOverview
import ai.prinim.prinyal.data.TopicSource
import ai.prinim.prinyal.data.ReplacementEntity
import ai.prinim.prinyal.domain.ContextPack
import kotlinx.coroutines.withContext
import ai.prinim.prinyal.domain.FeedView
import ai.prinim.prinyal.domain.PackPick
import ai.prinim.prinyal.domain.PersonIdentity
import ai.prinim.prinyal.domain.Replacements
import ai.prinim.prinyal.domain.StructureRepair
import ai.prinim.prinyal.domain.TokenSpend
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
import kotlinx.coroutines.flow.combine
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
        /** Окно заполнения дней задним числом (Д-50). */
        const val MISSING_DAYS_WINDOW = 14

        /** Сколько ждём разбора ответа, прежде чем спросить снова. */
        private const val PARSE_WAIT_TRIES = 40
        private const val PARSE_WAIT_STEP_MS = 500L
    }

    /**
     * Фильтр ленты (Д-25) и позиция списка.
     *
     * Живут во вьюмодели, а не в композиции: экран карточки пересоздаёт ленту,
     * и `remember` там умирает — человек возвращался в начало списка. Фильтр
     * при этом **не** переживает перезапуск: приложение открывается на клавише,
     * и вернуться к отфильтрованной ленте, не помня об этом, значит потерять
     * записи из виду.
     */
    private val _feedFilter = MutableStateFlow(FeedView.Filter.ALL)
    val feedFilter: StateFlow<FeedView.Filter> = _feedFilter

    fun setFeedFilter(filter: FeedView.Filter) {
        _feedFilter.value = filter
        // Смена фильтра — новый список: оставлять прежнюю позицию значит
        // открыть его посередине неизвестно чего.
        feedIndex = 0
        feedOffset = 0
    }

    /**
     * Пара однофамильцев, про которую стоит спросить (Д-30).
     *
     * Спрашиваем **только при совпавшей фамилии** — так решено дизайнером:
     * «Саня Иванов» и «Саша Иванов» вопрос заслуживают, «Саня» и «Саша»
     * порознь — нет, иначе продукт начнёт свататься к каждому созвучию.
     *
     * Квота та же, что у доспроса: одна карточка-вопрос в ленте. Поэтому
     * склейка уступает дорогу — сначала продукт узнаёт, кто это, и только
     * потом выясняет, не один ли это человек.
     */
    val mergeCandidate: StateFlow<Pair<PersonEntity, PersonEntity>?> =
        app.db.people().watchLive()
            .map { people ->
                people.firstNotNullOfOrNull { a ->
                    people.firstOrNull { b ->
                        a.id < b.id && PersonIdentity.mayBeSame(a.name, b.name)
                    }?.let { a to it }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** «Один» — записи и история съезжаются к первому имени. */
    fun mergePeople(from: PersonEntity, into: PersonEntity) = viewModelScope.launch {
        app.repository.mergePeople(from.id, into.id)
    }

    /**
     * «Разные» — фиксируем навсегда.
     *
     * Отказ хранится тем же полем, что и склейка, но указывает на самого себя:
     * строка перестаёт быть кандидатом, оставаясь видимой. Заводить второе поле
     * ради «нет» значило бы держать два способа сказать одно.
     */
    fun keepApart(a: PersonEntity, b: PersonEntity) = viewModelScope.launch {
        app.repository.keepPeopleApart(a.id, b.id)
    }

    /**
     * Люди (Д-26): только те, кого упоминают две разные записи.
     *
     * Пересчёт по прежним записям делается один раз при первом обращении:
     * пары человек↔запись пишутся при разборе, а весь накопленный корпус
     * разобран до того, как они появились, — иначе раздел молчал бы неделями.
     */
    val people = app.db.people().people(PersonIdentity.MIN_NOTES)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun ensurePeopleBackfilled() = viewModelScope.launch {
        if (app.settings.peopleBackfilled()) return@launch
        app.repository.backfillPeople()
        app.settings.setPeopleBackfilled()
    }

    /**
     * Черновик пака (Д-28): что показать на экране выбора.
     *
     * Экран открывается **до** сборки файла — и кнопкой в разделе, и голосом.
     * Раньше оба пути собирали молча и по-разному, отсюда и «наполняется
     * рандомно».
     */
    data class PackDraft(
        val title: String,
        val notes: List<ai.prinim.prinyal.data.NoteWithItems>,
        val picked: Set<String>,
    )

    private val _packDraft = MutableStateFlow<PackDraft?>(null)
    val packDraft: StateFlow<PackDraft?> = _packDraft

    fun openPackPick(topicId: String?, title: String) = viewModelScope.launch {
        val notes = withContext(Dispatchers.IO) { notesOnce(topicId) }
        _packDraft.value = PackDraft(title, notes, PackPick.preselected(notes))
    }

    /**
     * Пак по теме, названной голосом (Д-28).
     *
     * Раздел ищется по имени; не нашёлся — берём записи, где тема прозвучала
     * словом. Это тот же поиск, что был, но теперь он лишь **предлагает**
     * набор, а не решает за человека: экран выбора стоит между поиском и
     * файлом.
     */
    fun openPackPickByTopic(topic: String) = viewModelScope.launch {
        val notes = withContext(Dispatchers.IO) {
            val topicId = app.db.topics().live()
                .firstOrNull { PersonIdentity.norm(it.name) == PersonIdentity.norm(topic) }
                ?.id
            if (topicId != null) {
                notesOnce(topicId)
            } else {
                app.db.notes().all()
                    .filter { PersonIdentity.mentions(it.transcript.orEmpty(), topic) }
                    .map {
                        ai.prinim.prinyal.data.NoteWithItems(it, app.db.items().forNote(it.id))
                    }
                    .sortedByDescending { it.note.createdAt }
            }
        }
        _packDraft.value = PackDraft(topic, notes, PackPick.preselected(notes))
    }

    fun togglePacked(noteId: String) {
        val draft = _packDraft.value ?: return
        val picked = draft.picked.toMutableSet()
        if (!picked.remove(noteId)) picked += noteId
        _packDraft.value = draft.copy(picked = picked)
    }

    /** Долгий тап — «только эту»: снимает все остальные. */
    fun packOnly(noteId: String) {
        val draft = _packDraft.value ?: return
        _packDraft.value = draft.copy(picked = setOf(noteId))
    }

    fun closePackPick() {
        _packDraft.value = null
    }

    /** Собрать отмеченное и отдать файл наружу. */
    fun buildPack(onDone: (File) -> Unit) = viewModelScope.launch {
        val draft = _packDraft.value ?: return@launch
        val chosen = PackPick.chosen(draft.notes, draft.picked)
        val sources = chosen.map { ContextPack.Source(it.note, it.items) }
        val markdown = ContextPack.build(draft.title, sources)
        val file = withContext(Dispatchers.IO) {
            val dir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
            File(dir, ContextPack.fileName(draft.title)).apply { writeText(markdown) }
        }
        _packDraft.value = null
        onDone(file)
    }

    private suspend fun notesOnce(topicId: String?): List<ai.prinim.prinyal.data.NoteWithItems> {
        val all = app.db.notes().all().filter { !it.transcript.isNullOrBlank() }
        val picked = when (topicId) {
            null -> all.filter { it.topicId == null }
            else -> all.filter { it.topicId == topicId }
        }
        return picked.map {
            ai.prinim.prinyal.data.NoteWithItems(it, app.db.items().forNote(it.id))
        }.sortedByDescending { it.note.createdAt }
    }

    /**
     * «Собрать контекст» с карточки человека (Д-27): та же механика, что у
     * раздела, только выборка по упоминаниям.
     */
    fun contextPackForPerson(personId: String, name: String) = viewModelScope.launch {
        val notes = withContext(Dispatchers.IO) {
            app.db.people().notesOfOnce(personId).map {
                ai.prinim.prinyal.data.NoteWithItems(it, app.db.items().forNote(it.id))
            }
        }
        _packDraft.value = PackDraft(name, notes, PackPick.preselected(notes))
    }

    /**
     * «Рассказать» о человеке (Д-27): второй вход для факта.
     *
     * Открывает обычный захват с плашкой «про Веру» — тот же экран, которым
     * человек и записывает. Отдельной формы для факта нет: продукт слушает, а
     * не анкетирует.
     */
    fun tellAbout(context: android.content.Context, name: String, personId: String? = null) {
        context.startActivity(
            android.content.Intent(
                context,
                ai.prinim.prinyal.capture.CaptureActivity::class.java,
            ).apply {
                putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_ABOUT, name)
                personId?.let {
                    putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_ABOUT_ID, it)
                }
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
        )
    }

    /** Записи, где упомянут человек, — для его карточки (Д-27). */
    fun notesOfPerson(personId: String) = app.db.people().notesOf(personId)

    /** Фильтр раздела — свой, чтобы выбор в ленте не менял вид раздела. */
    private val _topicFilter = MutableStateFlow(FeedView.Filter.ALL)
    val topicFilter: StateFlow<FeedView.Filter> = _topicFilter

    fun setTopicFilter(filter: FeedView.Filter) {
        _topicFilter.value = filter
    }

    var feedIndex: Int = 0
    var feedOffset: Int = 0

    fun token(): String = app.settings.token

    fun note(id: String) = app.db.notes().watch(id)

    /** Связи заметки — обе стороны сразу (Р-15.11). */
    fun linked(id: String) = app.db.links().forNote(id)

    /**
     * Заметки раздела; `topicId == null` — «Без раздела».
     *
     * Сентинел, а не топик: раздел решений собирается запросом (Р-15.10).
     * Настоящие id — uuid, поэтому «@» в имени столкновение исключает.
     */
    fun notesOf(topicId: String?) = when (topicId) {
        null -> app.db.notes().withoutTopic()
        else -> app.db.notes().byTopic(topicId)
    }

    /** Сколько решений накопилось — от этого зависит, есть ли строка в списке. */

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

        // Новое правило применяется к тому, что уже распознано.
        //
        // Раньше оно ждало следующей записи — из соображения «транскрипт это
        // то, что было услышано тогда». Но человек заводит правило, глядя на
        // **этот** транскрипт и на **эту** ошибку: он поправил слово, а слово
        // осталось прежним, и правило выглядело несработавшим. Смысл словаря в
        // том, чтобы чинить распознавание, а не хранить его ошибки.
        //
        // Настоящей записью остаётся аудио: оно не трогается никогда. Пункты
        // тоже не пересобираются — их формулировки могут быть правлены рукой,
        // и переразбор человек запускает сам.
        val fixed = app.repository.applyRuleToTranscripts()
        if (fixed > 0) {
            app.analytics.log("replacement_backfill", mapOf("notes" to fixed))
        }
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

    /**
     * «Не повторять» (макеты 10c): вечное дело становится обычным, назначенным
     * на тот же день. Ничего не удаляется, поэтому и снекбар обещает не
     * «вернуть удалённое», а вернуть порядок.
     */
    fun stopRepeat(itemId: String) = viewModelScope.launch {
        val rule = app.db.items().byId(itemId)?.repeatRule ?: return@launch
        app.repository.stopRepeat(itemId) ?: return@launch
        _undo.value = UndoEvent(UndoMessage.RepeatStopped) {
            app.repository.resumeRepeat(itemId, rule)
        }
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
        /** «Больше не повторяю» — пункт остался жив, откат возвращает правило. */
        data object RepeatStopped : UndoMessage

        /** «Снова в плане» — закрытый пункт вернули (Р-18.4). */
        data object ItemRevived : UndoMessage

        /** Перенос на доске (1.5): строка называет новый день — «Завтра утром». */
        data class Moved(val label: String) : UndoMessage
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

    val bedtime: StateFlow<LocalTime> = app.settings.bedtime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalTime.of(21, 30))

    /** Смена «перед сном» переставляет аларм сразу: старое время уже неправда. */
    fun setBedtime(time: LocalTime) = viewModelScope.launch {
        app.settings.setBedtime(time)
        ai.prinim.prinyal.returns.DayAsk.schedule(getApplication(), time)
    }

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

    /**
     * Контекст-пак по разделу (Р-15.13).
     *
     * Собирается кодом из корпуса; модель не участвует, потому что бумагу
     * человек уносит наружу и там за неё отвечает уже он.
     *
     * @param topicId раздел; null — заметки без раздела
     */
    fun contextPack(topicId: String?, title: String, onDone: (File, String) -> Unit) =
        viewModelScope.launch {
            val notes = withContext(Dispatchers.IO) {
                val all = app.db.notes().all()
                val picked = if (topicId == null) {
                    all.filter { it.topicId == null }
                } else {
                    all.filter { it.topicId == topicId }
                }
                picked.map { ContextPack.Source(it, app.db.items().forNote(it.id)) }
            }
            val markdown = ContextPack.build(title, notes)
            val file = withContext(Dispatchers.IO) {
                val dir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
                File(dir, ContextPack.fileName(title)).apply { writeText(markdown) }
            }
            onDone(file, markdown)
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
    /** Предложение структуры показывают «Разделы» (Д-49): грузится само по себе. */
    fun loadStructure() = viewModelScope.launch { _structure.value = findStructureOffer() }

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

    /**
     * «Покрутить»: спросить об идее (Р-15.14).
     *
     * Негодный вопрос не показывается вовсе — режим просто закрывается. Пустой
     * или общий вопрос человеку хуже, чем его отсутствие: он сообщает, что
     * продукт не читал записи.
     */
    fun askAboutIdea(noteId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repository.askNext(noteId) }
    }


    /** Выход из режима — в любой момент и без последствий. */

    /** Расход на модель (Р-16.4): за неделю и за всё время. */
    private val _spend = MutableStateFlow<Pair<TokenSpend.Spend, TokenSpend.Spend>?>(null)
    val spend: StateFlow<Pair<TokenSpend.Spend, TokenSpend.Spend>?> = _spend

    fun loadSpend() = viewModelScope.launch {
        val events = app.analytics.readAll()
        val week = java.time.Instant.now().minus(7, java.time.temporal.ChronoUnit.DAYS)
        _spend.value = TokenSpend.of(events, week) to TokenSpend.of(events)
    }

    /** Последние падения (Р-17.1). */
    private val _crashes = MutableStateFlow<List<ai.prinim.prinyal.data.CrashLog.Record>>(emptyList())
    val crashes: StateFlow<List<ai.prinim.prinyal.data.CrashLog.Record>> = _crashes

    fun loadCrashes() = viewModelScope.launch {
        _crashes.value = withContext(Dispatchers.IO) { app.crashes.records() }
    }

    /** Отдать трейсы наружу — единственный способ показать их мне. */
    fun crashReport(): String = app.crashes.records(limit = 3).joinToString("\n\n") { record ->
        "=== ${record.at} · ${record.thread}\n${record.trace}"
    }

    fun clearCrashes() = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.crashes.clear() }
        _crashes.value = emptyList()
    }

    // --- поиск (Р-18.5) ---

    /** null — поиск закрыт; пустая строка — поле открыто, запроса ещё нет. */
    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery

    fun openSearch() {
        _searchQuery.value = ""
    }

    /** Поле не запоминает прошлый запрос (11a) — закрытие стирает. */
    fun closeSearch() {
        _searchQuery.value = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val searchResults: StateFlow<List<ai.prinim.prinyal.domain.NoteSearch.Result>> =
        kotlinx.coroutines.flow.combine(feed, _searchQuery) { notes, query ->
            if (query.isNullOrBlank()) emptyList()
            else ai.prinim.prinyal.domain.NoteSearch.search(notes, query)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- дни (Р-18.1) ---

    val days: StateFlow<List<ai.prinim.prinyal.data.DayEntity>> = app.db.days().watch()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Итог текущей недели (Р-18.3) и сколько вечеров рассказано. */
    val weekRecap: StateFlow<ai.prinim.prinyal.data.WeekRecapEntity?> =
        app.db.weekRecaps()
            .watch(java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val weekDays: StateFlow<List<ai.prinim.prinyal.data.DayEntity>> =
        days.map { all ->
            val monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString()
            all.filter { it.date >= monday }.sortedByDescending { it.date }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Дни без впечатления за две недели назад, без сегодняшнего (Д-50).
     * Дальше двух недель — не память о дне, а реконструкция.
     */
    val missingDays: StateFlow<List<String>> = days.map { all ->
        val told = all
            .filter { !it.line.isNullOrBlank() || !it.transcript.isNullOrBlank() }
            .map { it.date }
            .toSet()
        val today = java.time.LocalDate.now()
        (1..MISSING_DAYS_WINDOW)
            .map { today.minusDays(it.toLong()).toString() }
            .filter { it !in told }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- последняя корневая поверхность ---

    /** Корневые поверхности, к которым возвращаемся после записи. */
    fun rememberRoot(route: Route) {
        val key = when (route) {
            Route.Feed -> "feed"
            Route.Topics -> "topics"
            Route.Days -> "days"
            Route.Weekly -> "board"
            else -> return
        }
        viewModelScope.launch { app.settings.setLastRoot(key) }
    }

    suspend fun lastRoot(): Route = when (app.settings.lastRoot()) {
        "topics" -> Route.Topics
        "days" -> Route.Days
        "board" -> Route.Weekly
        else -> Route.Feed
    }

    // --- доска «Дела» (1.5, спека «Неделя доской») ---

    enum class BoardColumn { TODAY, TOMORROW, THIS_WEEK, LATER }

    /**
     * Карточка доски. [at] — момент, которым пункт попал в колонку: ручная дата
     * или ближайший возврат. У повтора [repeat] не null, и переносу он не
     * поддаётся (Д-54).
     */
    data class BoardCard(
        val item: ai.prinim.prinyal.data.ItemEntity,
        val note: ai.prinim.prinyal.data.NoteEntity,
        val topic: String?,
        val at: Long?,
        val repeat: ai.prinim.prinyal.domain.Repeat?,
        val overdue: Boolean,
    )

    data class Board(
        val inbox: List<BoardCard> = emptyList(),
        val today: List<BoardCard> = emptyList(),
        val tomorrow: List<BoardCard> = emptyList(),
        /** Ближайшие семь дней после завтра — то же окно, что у ленты (CLOSED_WINDOW_DAYS). */
        val week: List<BoardCard> = emptyList(),
        val later: List<BoardCard> = emptyList(),
        /** Сколько пунктов закрыто за семь дней — строка под доской, при 0 её нет. */
        val doneWeek: Int = 0,
    ) {
        fun column(c: BoardColumn): List<BoardCard> = when (c) {
            BoardColumn.TODAY -> today
            BoardColumn.TOMORROW -> tomorrow
            BoardColumn.THIS_WEEK -> week
            BoardColumn.LATER -> later
        }
    }

    /**
     * Доска считается от трёх потоков — записи с пунктами, возвраты, разделы —
     * и потому реактивна целиком: перенос карточки пересобирает возврат, а
     * возврат двигает карточку в новую колонку без единого ручного обновления.
     *
     * Колонку задаёт **ближайший возврат**, а не `dueAt`: у пункта с окном
     * `dueAt` пуст, и лента до 1.5 из-за этого считала его «позже». На доске
     * это была бы ложь: «напомню днём» — это сегодня.
     */
    val board: StateFlow<Board> = combine(
        app.db.notes().feed(),
        app.db.returns().watchAll(),
        topics,
    ) { rows, returns, topicList ->
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val startToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endTomorrow = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val endWeek = today.plusDays(ai.prinim.prinyal.domain.FeedView.CLOSED_WINDOW_DAYS)
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val weekAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(7)).toEpochMilli()
        val names = topicList.associate { it.id to it.name }
        val alive = setOf(ItemState.PLANNED, ItemState.RETURNED, ItemState.SNOOZED)
        val pending = returns.filter { it.firedAt == null }
            .groupBy { it.itemId }
            .mapValues { (_, list) -> list.minOf { it.scheduledAt } }

        val inbox = mutableListOf<BoardCard>()
        val cols = BoardColumn.entries.associateWith { mutableListOf<BoardCard>() }

        rows.forEach { row ->
            row.items.filter { ItemState.of(it.state) in alive }.forEach { item ->
                val repeat = ai.prinim.prinyal.domain.Repeat.of(item.repeatRule)
                val kind = ai.prinim.prinyal.data.DueKind.of(item.dueKind)
                val at = item.dueAt ?: pending[item.id]
                val card = BoardCard(
                    item = item,
                    note = row.note,
                    topic = row.note.topicId?.let { names[it] },
                    at = at,
                    repeat = repeat,
                    overdue = at != null && at < startToday,
                )
                when {
                    // Повтор стоит в «Сегодня» каждый день (Д-54).
                    repeat != null -> cols.getValue(BoardColumn.TODAY) += card
                    // Без срока и без окна — во «Входящие» (Д-51).
                    kind == ai.prinim.prinyal.data.DueKind.NONE -> inbox += card
                    // Окно назначено, возврат уже сработал и ждёт ответа — это
                    // сегодняшнее дело, а не потерянное.
                    at == null -> cols.getValue(BoardColumn.TODAY) += card
                    at < endToday -> cols.getValue(BoardColumn.TODAY) += card
                    at < endTomorrow -> cols.getValue(BoardColumn.TOMORROW) += card
                    at < endWeek -> cols.getValue(BoardColumn.THIS_WEEK) += card
                    else -> cols.getValue(BoardColumn.LATER) += card
                }
            }
        }

        // «Сегодня»: просроченные сверху, потом по времени, повторы в конце (§4).
        val todayCards = cols.getValue(BoardColumn.TODAY).sortedWith(
            compareBy<BoardCard> { it.repeat != null }
                .thenByDescending { it.overdue }
                .thenBy { it.at ?: Long.MAX_VALUE },
        )
        val doneWeek = returns
            .filter { it.action == "done" && (it.firedAt ?: 0L) >= weekAgo }
            .map { it.itemId }.distinct().size

        Board(
            inbox = inbox.sortedBy { it.note.createdAt },
            today = todayCards,
            tomorrow = cols.getValue(BoardColumn.TOMORROW).sortedBy { it.at ?: Long.MAX_VALUE },
            week = cols.getValue(BoardColumn.THIS_WEEK).sortedBy { it.at ?: Long.MAX_VALUE },
            later = cols.getValue(BoardColumn.LATER).sortedBy { it.at ?: Long.MAX_VALUE },
            doneWeek = doneWeek,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Board())

    private val _boardHintDone = MutableStateFlow(true)
    /** Фраза голоса в стопке: до первого удачного переноса, потом навсегда нет (§3). */
    val boardHintDone: StateFlow<Boolean> = _boardHintDone

    fun loadBoardHint() = viewModelScope.launch {
        _boardHintDone.value = app.settings.boardHintDone()
    }

    /** Снимок расписания пункта — чтобы «Вернуть» вернуло ровно то, что было. */
    private data class Schedule(val kind: ai.prinim.prinyal.data.DueKind, val window: Window?, val exactAt: Long?)

    private suspend fun scheduleOf(itemId: String): Schedule? {
        val item = app.db.items().byId(itemId) ?: return null
        return Schedule(
            ai.prinim.prinyal.data.DueKind.of(item.dueKind),
            Window.of(item.window),
            item.dueAt.takeIf { ai.prinim.prinyal.data.DueKind.of(item.dueKind) == ai.prinim.prinyal.data.DueKind.EXACT },
        )
    }

    private suspend fun restore(itemId: String, was: Schedule) {
        when (was.kind) {
            ai.prinim.prinyal.data.DueKind.NONE -> app.repository.editItem(itemId, clearSchedule = true)
            ai.prinim.prinyal.data.DueKind.EXACT -> app.repository.editItem(itemId, exactAt = was.exactAt)
            ai.prinim.prinyal.data.DueKind.WINDOW -> app.repository.editItem(itemId, window = was.window)
        }
    }

    /**
     * Перенос на доске: одна функция на все жесты. Правило продукта «срок
     * назначает речь» здесь сужено, а не отменено (бриф 1.5): речь — при
     * рождении дела, доска — при пересмотре.
     */
    private fun move(
        itemId: String,
        fromInbox: Boolean,
        label: String,
        apply: suspend () -> Unit,
    ) = viewModelScope.launch {
        val was = scheduleOf(itemId) ?: return@launch
        apply()
        if (fromInbox && !_boardHintDone.value) {
            app.settings.setBoardHintDone()
            _boardHintDone.value = true
        }
        app.analytics.log(
            "board_move",
            mapOf("item" to itemId, "from_inbox" to fromInbox, "to" to label),
        )
        _undo.value = UndoEvent(UndoMessage.Moved(label)) { restore(itemId, was) }
    }

    /**
     * Момент «день D в окно W» от сегодняшнего числа.
     *
     * Окна пункта (`window = …`) считаются от даты записи, а не от сегодня
     * (Scheduler.scheduleFor): для дела трёхнедельной давности «завтра утром» —
     * это давно прошедшее утро, и планировщик молча уводил его в ближайшее
     * окно. Карточка ложилась в «Сегодня», а строка отмены говорила «Завтра
     * утром». Доска назначает день пальцем — значит, точной датой от сегодня.
     */
    private suspend fun boardInstant(daysFromToday: Long, window: Window): Long {
        val zone = java.time.ZoneId.systemDefault()
        return java.time.LocalDate.now(zone).plusDays(daysFromToday)
            .atTime(app.settings.windowsNow().timeOf(window))
            .atZone(zone).toInstant().toEpochMilli()
    }

    /** Свайп вправо из стопки или из «Сегодня»: завтра утром (§5.1). */
    fun moveTomorrow(itemId: String, fromInbox: Boolean = false) = viewModelScope.launch {
        val at = boardInstant(1, Window.MORNING)
        move(itemId, fromInbox, app.getString(R.string.board_moved_tomorrow)) {
            app.repository.editItem(itemId, exactAt = at)
        }
    }

    /** Свайп влево из «Завтра» или drag в «Сегодня»: ближайшее окно сегодня. */
    fun moveToday(itemId: String, fromInbox: Boolean = false) = viewModelScope.launch {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalTime.now(zone)
        val windows = app.settings.windowsNow()
        // Ближайшее окно, которое ещё впереди; вечером позже вечернего — через
        // час, но сегодня: человек сказал «сегодня», и это должно остаться сегодня.
        val window = listOf(Window.MORNING, Window.DAY, Window.EVENING)
            .firstOrNull { windows.timeOf(it).isAfter(now) }
        val at = if (window != null) {
            boardInstant(0, window)
        } else {
            // Все окна прошли: через час, но не позже конца сегодняшнего дня —
            // иначе «Сегодня вечером» ложилось бы в «Завтра».
            val endOfDay = java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone)
                .toInstant().toEpochMilli() - 60_000L
            minOf(System.currentTimeMillis() + 60L * 60 * 1000, endOfDay)
        }
        val label = app.getString(
            R.string.board_moved_today,
            if (window != null) ai.prinim.prinyal.domain.Phrases.windowLabel(app, window)
            else ai.prinim.prinyal.domain.Phrases.windowLabel(app, Window.EVENING),
        )
        move(itemId, fromInbox, label) { app.repository.editItem(itemId, exactAt = at) }
    }

    /** В «Неделю» — первый её день: послезавтра утром. Точный день — через шторку. */
    fun moveThisWeek(itemId: String, fromInbox: Boolean = false) = viewModelScope.launch {
        val at = boardInstant(2, Window.MORNING)
        move(itemId, fromInbox, app.getString(R.string.board_moved_after_tomorrow)) {
            app.repository.editItem(itemId, exactAt = at)
        }
    }

    /** В «Позже» — первый день за окном недели: через семь дней утром. */
    fun moveNextWeek(itemId: String) = viewModelScope.launch {
        val at = boardInstant(ai.prinim.prinyal.domain.FeedView.CLOSED_WINDOW_DAYS, Window.MORNING)
        move(itemId, false, app.getString(R.string.board_moved_next_week)) {
            app.repository.editItem(itemId, exactAt = at)
        }
    }

    /** Свайп влево из «Сегодня»: снять срок — обратно во «Входящие». */
    fun moveToInbox(itemId: String) = move(
        itemId, false, app.getString(R.string.board_moved_inbox),
    ) { app.repository.editItem(itemId, clearSchedule = true) }

    /** Итог недели в «Днях» (§7): самое старое живое дело и разложено из входящих за 7 дней. */
    private val _recapMetrics = MutableStateFlow<Pair<Int, Int>?>(null)
    val recapMetrics: StateFlow<Pair<Int, Int>?> = _recapMetrics

    fun loadRecapMetrics() = viewModelScope.launch {
        val oldest = ai.prinim.prinyal.domain.WeekSignal(app.db).build().oldestWaitingDays
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val moved = app.analytics.readAll().count { e ->
            e.optString("e") == "board_move" &&
                e.optLong("t") >= weekAgo &&
                e.optBoolean("from_inbox")
        }
        _recapMetrics.value = oldest to moved
    }

    // --- «В план» (Р-18.4) ---

    fun reviveItem(itemId: String) = viewModelScope.launch {
        val was = app.repository.reviveItem(itemId) ?: return@launch
        _undo.value = UndoEvent(UndoMessage.ItemRevived) {
            app.repository.unreviveItem(itemId, was)
        }
    }

    /**
     * «Пропустить вопрос» (спека §7): один тап, и сразу следующий.
     *
     * Пропуск не прячется: он уходит в историю и считается моделью — два
     * подряд, и она свернётся в резюме вместо нового вопроса. Это и есть
     * способ сказать «хватит», не выходя из разговора.
     */
    fun skipQuestion(noteId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            app.repository.skipQuestion(noteId)
            app.repository.askNext(noteId)
        }
    }

    /** «Прекратить» (Р-21.2): выход из разговора, и ничего больше. */
    fun stopInterview(noteId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            app.db.notes().setInterview(
                noteId,
                ai.prinim.prinyal.data.InterviewState.NONE.wire,
            )
        }
        app.analytics.log("interview_stop", mapOf("note" to noteId))
    }

    /**
     * Рассказать про день руками (Р-23.1).
     *
     * Вечерний вопрос приходит один раз и молчит, если на него не ответили, —
     * это правило продукта, и оно остаётся. Но до сих пор оно значило и другое:
     * пропустил уведомление — день потерян навсегда. Теперь вход есть и в
     * «Днях», тем же экраном записи и с той же плашкой.
     */
    fun tellAboutDay(context: android.content.Context) {
        context.startActivity(
            android.content.Intent(
                context,
                ai.prinim.prinyal.capture.CaptureActivity::class.java,
            ).apply {
                putExtra(
                    ai.prinim.prinyal.capture.CaptureActivity.EXTRA_DAY,
                    java.time.LocalDate.now().toString(),
                )
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
        )
    }

    /** Последний вопрос разговора (Р-21.1) — из базы, не из памяти. */
    fun lastQuestion(noteId: String) = app.db.questions().watchLast(noteId)

    /** Поправить впечатление дня руками (Р-24.3). */
    fun editDayLine(date: String, line: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repository.editDayLine(date, line) }
    }

    /**
     * Рассказать про день заново голосом (Р-24.3).
     *
     * Тот же экран записи и та же дата: запись по дате перезаписывает прежнюю,
     * и день пересобирается целиком — со свежей расшифровкой и новой строкой.
     */
    fun retellDay(context: android.content.Context, date: String) {
        context.startActivity(
            android.content.Intent(
                context,
                ai.prinim.prinyal.capture.CaptureActivity::class.java,
            ).apply {
                putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_DAY, date)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
        )
    }

    /** Переименовать человека (Р-25.3): имя приходит из речи и с ошибками. */
    fun renamePerson(personId: String, name: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repository.renamePerson(personId, name) }
    }

    /** Удалить знание о человеке (Р-25.4). Записи остаются: там его слова. */
    fun deletePerson(personId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repository.deletePerson(personId) }
    }

    /** Склеить дубль руками (Р-25.2): записи и факты переезжают. */
    fun mergePeopleById(fromId: String, intoId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { app.repository.mergePeople(fromId, intoId) }
    }

    /** Кого можно предложить как второго в склейке — все, кроме себя. */
    val allPeople = app.db.people().watchLive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Факты о человеке (Р-20.1): до трёх, слитым абзацем на карточке. */
    fun factsOfPerson(personId: String) = app.db.personFacts().watch(personId)

    fun showMessage(text: String?) {
        _message.value = text
    }
}
