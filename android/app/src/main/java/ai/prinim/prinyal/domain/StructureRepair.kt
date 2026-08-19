package ai.prinim.prinyal.domain

/**
 * Починка структуры (Р-15.12).
 *
 * Раз в неделю продукт смотрит, не разъехалась ли раскладка: раздел разросся и
 * внутри него живёт своя тема; заметок без раздела накопилось столько, что они
 * уже про одно.
 *
 * Ключевое ограничение здесь не алгоритм, а **такт**. Предложение перекроить
 * структуру — это продукт, который просит человека поработать: даже когда он
 * прав, частота превращает его из помощника в назойливого. Поэтому не больше
 * одного предложения в неделю на всё, и отказ по кластеру закрывает эту тему на
 * месяц.
 *
 * Кластер ищется по словам (BM25 из Р-15.15) и по связям (Р-15.11), а не по
 * эмбеддингам: спайк показал, что на этом корпусе они не лучше
 * (`docs/spike-1_0_2-semsearch.md`).
 */
object StructureRepair {

    /** С какого размера раздел вообще имеет смысл делить. */
    const val BIG_TOPIC = 15

    /** Сколько сирот должно накопиться, прежде чем предлагать им раздел. */
    const val ORPHANS = 5

    /** Какую долю раздела должен занять кластер, чтобы стать отдельной темой. */
    const val CLUSTER_SHARE = 0.34

    /** Минимальный размер кластера — меньше трёх это не тема, а совпадение. */
    const val MIN_CLUSTER = 3

    /** Не чаще раза в неделю. */
    const val QUIET_DAYS = 7L

    /** Отказ по кластеру закрывает его на месяц. */
    const val REFUSED_DAYS = 30L

    /** Слова короче — предлоги и связки, темы они не задают. */
    private const val MIN_WORD = 4

    /** Тот же набор символов, что у токенизатора: имя берётся из сырого текста. */
    private val WORD = Regex("[а-яёa-z0-9]+")

    /**
     * Слова, которыми раздел не называют. Они встречаются в половине записей и
     * дают группу, у которой нет темы, — а человеку показывают её как тему.
     */
    private val STOP: Set<String> = setOf(
        "нужно", "надо", "было", "будет", "может", "мысль", "мысли", "дела", "делать",
        "сделать", "просто", "очень", "тоже", "поэтому", "который", "этого", "одна",
        "одно", "ещё", "есть", "чтобы", "потом", "сейчас", "только", "всего", "номер",
        // Список хранится словами, а сравнивается основами: иначе «одна» в
        // списке и «одн» в ключе не встретятся, и стоп-слово тихо не сработает.
    ).map(::stem).toSet()

    data class Note(val id: String, val text: String)

    /** Что предлагаем сделать. */
    sealed interface Offer {
        /** Выделить кластер из разросшегося раздела в свой. */
        data class Split(
            val topicId: String,
            val topicName: String,
            /** Слово, которым группа держится, — им же предложение и объясняется. */
            val word: String,
            val noteIds: List<String>,
        ) : Offer

        /** Собрать похожих сирот в новый раздел. */
        data class Gather(val word: String, val noteIds: List<String>) : Offer
    }

    /**
     * Устойчивая группа внутри списка.
     *
     * Нам нужна **одна** группа, которую не стыдно показать и которую можно
     * назвать словами, а не разбиение всего раздела. Молчать здесь безопаснее,
     * чем говорить.
     *
     * @param linked пары заметок, связанных явно (Р-15.11) — они весят больше
     *        любой похожести слов, потому что за ними стоит суждение модели,
     *        проверенное валидатором
     */
    fun cluster(notes: List<Note>, linked: Set<Pair<String, String>> = emptySet()): Cluster? {
        if (notes.size < MIN_CLUSTER) return null

        // Группа задаётся **общим словом**, а не близостью в пространстве.
        //
        // Первые две версии искали кластер похожестью: сначала порогом по
        // счёту, потом взаимным соседством со связными компонентами. Обе
        // склеивали две темы в одну группу — достаточно одной случайной пары
        // между ними, — и продукт предлагал «выделить раздел» из смеси.
        // Подкручивание порогов сдвигало границу, но не убирало причину.
        //
        // Общее слово убирает её по построению: группа однородна, потому что
        // однородность и есть её определение. И, что важнее, такую группу можно
        // **назвать**: предложение звучит как «пять записей про маркдаун», а не
        // как «продукт нашёл кластер». Человеку предъявляют основание, а не
        // результат работы алгоритма.
        // Длину проверяем **до** усечения: «даче» — годное слово, а его основа
        // «дач» короче порога, и фильтр по ней выбрасывал бы ровно те слова,
        // ради которых усечение и делалось.
        val words = notes.associate { note ->
            note.id to Bm25.tokenize(note.text)
                .filter { it.length >= MIN_WORD }
                .map(::stem)
                .toSet()
        }
        val byWord = mutableMapOf<String, MutableList<String>>()
        words.forEach { (id, set) ->
            set.forEach { word -> byWord.getOrPut(word) { mutableListOf() } += id }
        }

        // Полная форма слова — та, что прозвучала.
        //
        // Берётся из **сырого** текста, а не из токенов: Bm25.tokenize рубит
        // слова длиннее шести букв до пяти, и «маркдаун» приезжает оттуда уже
        // как «маркд». Первая попытка чинила обрубок обрубком — на экране
        // получилось «выделить раздел „Маркд"», что ничем не лучше «Одн».
        val fullForm = mutableMapOf<String, String>()
        notes.forEach { note ->
            WORD.findAll(note.text.lowercase())
                .map { it.value }
                .filter { it.length >= MIN_WORD }
                .forEach { raw -> fullForm.putIfAbsent(stem(Bm25.tokenize(raw).first()), raw) }
        }

        val best = byWord
            // Слово, встречающееся почти везде, темы не выделяет: оно и есть
            // раздел. «Дача» в разделе «Дача» — не новость.
            .filterValues { it.size >= MIN_CLUSTER && it.size < notes.size }
            // Служебные слова темой не бывают: «нужно», «одна», «сделать»
            // встречаются в половине записей и назовут раздел ни о чём.
            .filterKeys { it !in STOP }
            // При равном размере берём слово длиннее: «маркдаун» содержательнее
            // «мысли», а группу они дают одну и ту же.
            .maxWithOrNull(compareBy({ it.value.size }, { it.key.length }))

        // Слова не нашли ничего — но связи могли: две записи бывают про одно, не
        // разделив ни корня, и ради этого случая линковка и существует.
        val members = (best?.value ?: linkedGroup(notes, linked) ?: return null).toMutableSet()
        // Связанные явно (Р-15.11) подтягиваются к группе, даже если слова у
        // них другие: за связью стоит суждение модели, прошедшее валидатор.
        linked.forEach { (a, b) ->
            if (a in members && b in words.keys) members += b
            if (b in members && a in words.keys) members += a
        }
        if (members.size < MIN_CLUSTER || members.size == notes.size) return null
        val name = best?.key?.let { fullForm[it] }.orEmpty()
        return Cluster(word = name, noteIds = members.toList())
    }

    /**
     * Найденная группа и слово, которым она держится.
     *
     * [word] пуст, когда группу собрали связи, а не слова: тогда предложение
     * говорит о самих записях, а не о теме.
     */
    data class Cluster(val word: String, val noteIds: List<String>)

    /** Связные по линкам — запасной путь, когда общего слова нет. */
    private fun linkedGroup(
        notes: List<Note>,
        linked: Set<Pair<String, String>>,
    ): List<String>? {
        val ids = notes.map { it.id }.toSet()
        val edges = mutableMapOf<String, MutableSet<String>>()
        linked.forEach { (a, b) ->
            if (a in ids && b in ids) {
                edges.getOrPut(a) { mutableSetOf() } += b
                edges.getOrPut(b) { mutableSetOf() } += a
            }
        }
        if (edges.isEmpty()) return null
        val seen = mutableSetOf<String>()
        var best = emptyList<String>()
        ids.forEach { id ->
            if (!seen.add(id)) return@forEach
            val group = mutableListOf(id)
            val queue = ArrayDeque(edges[id].orEmpty())
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!seen.add(next)) continue
                group += next
                queue += edges[next].orEmpty()
            }
            if (group.size > best.size) best = group
        }
        return best.takeIf { it.size >= MIN_CLUSTER }
    }

    /**
     * Русское окончание долой: «дача», «даче», «дачи», «дачу» — одно слово, и
     * группа обязана их не различать.
     *
     * Отдельно от [Bm25.tokenize] намеренно: та считает похожесть, и её формула
     * с точностью до знака совпадает с замером спайка
     * (`docs/spike-1_0_2-semsearch.md`). Трогать её ради группировки значило бы
     * рассогласовать отчёт и код.
     */
    private fun stem(word: String): String =
        word.trimEnd('а', 'я', 'о', 'е', 'ы', 'и', 'у', 'ю', 'й', 'ь').ifEmpty { word }

    /**
     * @return что предложить, или null — молчим
     */
    fun offer(
        bigTopics: List<Pair<Topic, List<Note>>>,
        orphans: List<Note>,
        linked: Set<Pair<String, String>> = emptySet(),
        refused: Set<String> = emptySet(),
    ): Offer? {
        // Разросшийся раздел разбираем первым: там структура уже мешает, а
        // сироты просто лежат.
        bigTopics.forEach { (topic, notes) ->
            if (notes.size < BIG_TOPIC) return@forEach
            if (topic.id in refused) return@forEach
            val group = cluster(notes, linked) ?: return@forEach
            // Кластер должен быть заметной частью раздела: три заметки из
            // сорока — не «половина раздела про другое», а три заметки.
            if (group.noteIds.size >= notes.size * CLUSTER_SHARE) {
                return Offer.Split(topic.id, topic.name, group.word, group.noteIds)
            }
        }

        if (orphans.size >= ORPHANS && ORPHANS_KEY !in refused) {
            val group = cluster(orphans, linked)
            if (group != null) return Offer.Gather(group.word, group.noteIds)
        }
        return null
    }

    data class Topic(val id: String, val name: String)

    /**
     * Можно ли вообще заговорить сейчас.
     *
     * Такт здесь важнее находок: два предложения в неделю превращают продукт из
     * помощника в того, кто требует навести порядок. Поэтому проверка стоит
     * **до** поиска, а не после — иначе соблазн «ну это же важная находка»
     * рано или поздно победит.
     */
    fun maySpeak(lastOfferAt: Long?, now: Long): Boolean =
        lastOfferAt == null || now - lastOfferAt >= QUIET_DAYS * DAY_MS

    /** Действует ли ещё отказ по этой теме. */
    fun refusalHolds(refusedAt: Long?, now: Long): Boolean =
        refusedAt != null && now - refusedAt < REFUSED_DAYS * DAY_MS

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Чем помечен отказ по сиротам: у них нет своего id. */
    const val ORPHANS_KEY = "@orphans"
}
