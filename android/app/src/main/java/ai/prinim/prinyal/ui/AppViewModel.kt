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

    /**
     * Вопрос интервьюера по открытой заметке (Р-15.14).
     *
     * `null` — режим закрыт; пустая строка — режим открыт, вопрос ещё идёт.
     */
    private val _question = MutableStateFlow<String?>(null)
    val question: StateFlow<String?> = _question

    /** Предложение починить структуру (Р-15.12). null — продукт молчит. */
    private val _structure = MutableStateFlow<StructureRepair.Offer?>(null)
    val structure: StateFlow<StructureRepair.Offer?> = _structure

    /** Наблюдения недели (Р-15.9). Пусто — значит рассказывать нечего. */
    private val _weeklyFacts = MutableStateFlow<List<WeeklyFacts.Fact>>(emptyList())
    val weeklyFacts: StateFlow<List<WeeklyFacts.Fact>> = _weeklyFacts

    /** Идёт пересборка «Собрано» после разговора — экран говорит об этом. */
    private val _polishing = MutableStateFlow(false)
    val polishing: StateFlow<Boolean> = _polishing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    companion object {
        /** Псевдо-раздел решений: собирается запросом, топиком не является. */
        const val DECISIONS = "@decisions"

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
    fun tellAbout(context: android.content.Context, name: String) {
        context.startActivity(
            android.content.Intent(
                context,
                ai.prinim.prinyal.capture.CaptureActivity::class.java,
            ).apply {
                putExtra(ai.prinim.prinyal.capture.CaptureActivity.EXTRA_ABOUT, name)
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
        _question.value = ""
        val question = withContext(Dispatchers.IO) {
            val note = app.db.notes().byId(noteId) ?: return@withContext null
            val idea = app.repository.joinedTranscript(noteId).ifBlank {
                note.transcript.orEmpty()
            }
            if (idea.isBlank()) return@withContext null
            val asked = app.db.questions().forNote(noteId).map { it.text }
            val fresh = app.llm.interview(idea, asked) ?: return@withContext null
            app.db.questions().insert(
                ai.prinim.prinyal.data.QuestionEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    noteId = noteId,
                    text = fresh,
                    askedAt = System.currentTimeMillis(),
                )
            )
            fresh
        }
        _question.value = question
        if (question == null) _message.value = null
    }

    /**
     * Вернулись с ответом — спрашиваем дальше.
     *
     * Ждём, пока ответ разберётся: вопрос строится по всему тексту заметки, и
     * заданный по старому тексту он повторил бы сам себя слово в слово. Ждём
     * не вечно — если разбор не пришёл (сеть), режим просто остаётся открытым
     * без вопроса, и человек нажмёт сам.
     */
    fun resumeInterview(noteId: String) = viewModelScope.launch {
        _question.value = ""
        val ready = withContext(Dispatchers.IO) {
            repeat(PARSE_WAIT_TRIES) {
                val status = app.db.notes().byId(noteId)?.status
                if (status == ai.prinim.prinyal.data.NoteStatus.PARSED.wire) return@withContext true
                kotlinx.coroutines.delay(PARSE_WAIT_STEP_MS)
            }
            false
        }
        if (!ready) {
            _question.value = null
            return@launch
        }
        askAboutIdea(noteId)
    }

    /** Выход из режима — в любой момент и без последствий. */
    fun closeInterview() {
        _question.value = null
    }

    /**
     * «Закончить»: пересобрать «Собрано» с учётом разговора (Р-16.3).
     *
     * Пинг-понг менял текст заметки — ответы приходили сегментами и уходили в
     * общий разбор, — но «Собрано» оставалось прежним: человек видел старый
     * пересказ под свежим разговором. Здесь модель перечитывает всё вместе и
     * пишет **отдельный** блок «Что докрутили»: изначальный замысел остаётся
     * на месте, и видно, что доросло в разговоре, а что было с самого начала.
     */
    fun finishInterview(noteId: String) = viewModelScope.launch {
        _question.value = null
        _polishing.value = true
        val done = withContext(Dispatchers.IO) {
            val note = app.db.notes().byId(noteId) ?: return@withContext false
            val text = app.repository.joinedTranscript(noteId).ifBlank { note.transcript.orEmpty() }
            if (text.isBlank()) return@withContext false
            val questions = app.db.questions().forNote(noteId)

            // Без единого ответа докручивать нечего — и просить модель об этом
            // нельзя. Проверено живьём: на вопрос «пересоздаст суммаризацию или
            // запустит распознавание заново?» она сама же и ответила, и ответ
            // ушёл в заметку как слова человека. Правило «только сказанное»
            // держится кодом, а не просьбой в промпте: промпт — это пожелание,
            // а здесь цена ошибки — выдумка в собственных записях.
            if (!app.repository.answeredAfterAsking(noteId)) return@withContext false

            val block = app.llm.polish(text, note.bodyMd.orEmpty(), questions.map { it.text })
                ?: return@withContext false
            app.repository.appendToBody(noteId, block)
            true
        }
        _polishing.value = false
        if (!done) {
            _message.value =
                getApplication<Application>().getString(R.string.interview_polish_nothing)
        }
    }

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

    // --- «В план» (Р-18.4) ---

    fun reviveItem(itemId: String) = viewModelScope.launch {
        val was = app.repository.reviveItem(itemId) ?: return@launch
        _undo.value = UndoEvent(UndoMessage.ItemRevived) {
            app.repository.unreviveItem(itemId, was)
        }
    }

    fun showMessage(text: String?) {
        _message.value = text
    }
}
