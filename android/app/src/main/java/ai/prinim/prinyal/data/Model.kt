package ai.prinim.prinyal.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Модель данных PRD §3. Устройство — источник истины: бэкенд может падать, петля
 * деградирует, но данные не теряются и не молчат.
 */

enum class ItemType(val wire: String) {
    BUY("buy"), DO("do"), TELL("tell"), DATE("date"), THOUGHT("thought"), FACT("fact"),

    /**
     * Принятое решение (Р-15.10). Возвратов не порождает: решение уже принято,
     * напоминать о нём нечего. Если из решения следует действие — это отдельный
     * пункт, и только когда действие прозвучало.
     */
    DECISION("decision");

    companion object {
        /** Неизвестный тип не роняет разбор — он становится мыслью (инвариант §3). */
        fun of(wire: String?): ItemType =
            entries.firstOrNull { it.wire == wire } ?: THOUGHT
    }
}

enum class DueKind(val wire: String) {
    NONE("none"), WINDOW("window"), EXACT("exact");

    companion object {
        fun of(wire: String?): DueKind = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

enum class Window(val wire: String) {
    MORNING("morning"),
    DAY("day"),
    EVENING("evening"),
    TOMORROW_MORNING("tomorrow_morning"),
    WEEKEND("weekend");

    companion object {
        fun of(wire: String?): Window? = entries.firstOrNull { it.wire == wire }
    }
}

enum class Confidence(val wire: String) {
    HIGH("high"), MEDIUM("medium"), LOW("low");

    companion object {
        /** Неизвестная уверенность — не «high»: продукт не выглядит увереннее, чем есть. */
        fun of(wire: String?): Confidence = entries.firstOrNull { it.wire == wire } ?: LOW
    }
}

enum class NoteStatus(val wire: String) {
    RECORDED("recorded"),
    QUEUED("queued"),
    SENT("sent"),
    PARSED("parsed"),
    FAILED_ASR("failed_asr"),
    FAILED_LLM("failed_llm");

    companion object {
        fun of(wire: String?): NoteStatus = entries.firstOrNull { it.wire == wire } ?: RECORDED
    }
}

enum class ItemState(val wire: String) {
    PLANNED("planned"),
    RETURNED("returned"),
    DONE("done"),
    SNOOZED("snoozed"),
    DISMISSED("dismissed"),
    EXPIRED("expired");

    companion object {
        fun of(wire: String?): ItemState = entries.firstOrNull { it.wire == wire } ?: PLANNED
    }
}

enum class ReturnAction(val wire: String) {
    DONE("done"), LATER("later"), MISS("miss"), IGNORED("ignored");

    companion object {
        fun of(wire: String?): ReturnAction? = entries.firstOrNull { it.wire == wire }
    }
}

/** Источник жеста — нужен аналитике §F-9, чтобы понять, какая кнопка живёт. */
/** Откуда взялся топик заметки. */
enum class TopicSource(val wire: String) {
    NONE("none"), LLM("llm"), USER("user"),

    /** Раскладка предложена починкой структуры, человек согласился (Р-15.12). */
    REPAIR("repair");

    companion object {
        fun of(wire: String?): TopicSource =
            entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/** Что за запись — для формы карточки и будущих жанров (scope 1.0.1 Р-14.4). */
enum class NoteKind(val wire: String) {
    TASKS("tasks"), IDEA("idea"), QUESTION("question"), FACTS("facts"), MIXED("mixed"),

    /** Запись о том, что решили или договорились (Р-15.10). */
    DECISION("decision");

    companion object {
        fun of(wire: String?): NoteKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Как рождён раздел. */
enum class TopicKind(val wire: String) {
    SEEDED("seeded"), AUTO("auto"), MANUAL("manual");

    companion object {
        fun of(wire: String?): TopicKind = entries.firstOrNull { it.wire == wire } ?: AUTO
    }
}

enum class CaptureSource(val wire: String) {
    ICON("icon"), WIDGET("widget"), TILE("tile");

    companion object {
        fun of(wire: String?): CaptureSource = entries.firstOrNull { it.wire == wire } ?: ICON
    }
}

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    /** Момент нажатия, не момент сохранения: «завтра утром» считается от него. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    val transcript: String? = null,
    val status: String = NoteStatus.RECORDED.wire,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "source") val source: String = CaptureSource.ICON.wire,
    /** Код деградации из meta.degraded — приложение переводит его в строку §4.1. */
    @ColumnInfo(name = "degraded") val degraded: String? = null,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    /**
     * Мягкое удаление (спека R1.1 §2.2): пока живёт снекбар, запись можно вернуть.
     * Из всех выборок скрыта; окончательная зачистка — по истечении снекбара или
     * при выходе с экрана.
     */
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /**
     * Раздел, к которому отнесена запись. Топик — атрибут **заметки**: владелец
     * диктует с ясным намерением, пункты наследуют его через `note_id` и своего
     * поля не имеют (решение владельца 16.08, scope 1.0.1 §0).
     */
    @ColumnInfo(name = "topic_id") val topicId: String? = null,
    /** Кто отнёс: `llm` · `user` · `none`. Ручной выбор машина не перезаписывает. */
    @ColumnInfo(name = "topic_source") val topicSource: String = TopicSource.NONE.wire,
    /** Что это за запись: список дел, идея, вопрос, факты, смесь. */
    @ColumnInfo(name = "note_kind") val noteKind: String? = null,
    /** Тело заметки в markdown — только у идей (Р-14.5). */
    @ColumnInfo(name = "body_md") val bodyMd: String? = null,
    /**
     * Вторая половина разделённой записи (Р-15.5).
     *
     * Ссылка двусторонняя: у каждой половины стоит id другой. Так «склеить
     * обратно» работает с любой из них, а не только с первой.
     */
    @ColumnInfo(name = "sibling_id") val siblingId: String? = null,
)

/**
 * Раздел. Рождается из разбора или из рук — «создать впрок» в продукте нет.
 *
 * `nameNorm` держит уникальность без учёта регистра и пробелов: без него
 * «Продукт» и «продукт» разошлись бы в два раздела на второй же записи.
 */
@Entity(tableName = "topics", indices = [Index(value = ["name_norm"], unique = true)])
data class TopicEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
    /** `seeded` · `auto` · `manual` — откуда взялся. */
    val kind: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("note_id"), Index("state")],
)
data class ItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val type: String,
    val text: String,
    val who: String? = null,
    @ColumnInfo(name = "due_kind") val dueKind: String,
    val window: String? = null,
    @ColumnInfo(name = "due_at") val dueAt: Long? = null,
    val state: String = ItemState.PLANNED.wire,
    val confidence: String,
    /** Кусок транскрипта-источника: доверие и отладка. */
    @ColumnInfo(name = "raw_span") val rawSpan: String? = null,
    @ColumnInfo(name = "position") val position: Int = 0,
    @ColumnInfo(name = "edited") val edited: Boolean = false,
    /**
     * Прежняя формулировка, если пункт уменьшали или переформулировали
     * (Р-15.8). Продукт переписал слова человека — и обязан показать, какие
     * именно: замена без следа неотличима от подмены.
     */
    @ColumnInfo(name = "previous_text") val previousText: String? = null,
    /**
     * Правило повтора (Р-16.2): `weekly:mon`, `monthly:14`, `daily` или null.
     *
     * Строкой, а не парой колонок: правил три, читаются они глазом в дампе, а
     * колонка «день недели», пустая у месячных, врала бы про схему.
     */
    @ColumnInfo(name = "repeat_rule") val repeatRule: String? = null,
    /**
     * Когда повтор сделали в последний раз и когда он вернётся.
     *
     * Лежат на пункте, а не собираются из таблицы возвратов, потому что их
     * читает каждая строка ленты: джойн на строку — это джойн на прокрутку.
     */
    @ColumnInfo(name = "repeat_done_at") val repeatDoneAt: Long? = null,
    @ColumnInfo(name = "repeat_next_at") val repeatNextAt: Long? = null,
    /**
     * Когда закрытый пункт вернули в план (Р-18.4, «В план»).
     *
     * Возврат — не стирание, а событие: «сделал» остаётся в истории, под ним
     * встаёт «вернул в план». Дата и есть эта строка истории.
     */
    @ColumnInfo(name = "revived_at") val revivedAt: Long? = null,
)

@Entity(
    tableName = "returns",
    foreignKeys = [
        ForeignKey(
            entity = ItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["item_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("item_id"), Index("scheduled_at")],
)
data class ReturnEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "item_id") val itemId: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "fired_at") val firedAt: Long? = null,
    val action: String? = null,
    /** 1 | 2 — третьего захода нет: дальше R2-разбор, а не долбёж. */
    val attempt: Int = 1,
)

/**
 * Правило словаря автозамен (Р-14.2).
 *
 * Чинит не текст, а слух: распознавание стабильно ошибается на одних и тех же
 * именах и терминах. Правило применяется к новым транскриптам и уходит
 * глоссарием в промпт разбора — одно лечит написание, другое понимание.
 */
@Entity(tableName = "replacements", indices = [Index(value = ["from_norm"], unique = true)])
data class ReplacementEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_phrase") val fromPhrase: String,
    /** Нормализованный вид для поиска: нижний регистр, одиночные пробелы. */
    @ColumnInfo(name = "from_norm") val fromNorm: String,
    @ColumnInfo(name = "to_phrase") val toPhrase: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Сколько раз сработало. «Ни разу» — повод убрать правило. */
    val hits: Int = 0,
)

/**
 * Сегмент записи (Р-14.3). Заметка становится многосегментной, когда к ней
 * дописывают голосом; транскрипт заметки — конкатенация сегментов.
 */
@Entity(
    tableName = "note_segments",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("note_id")],
)
data class SegmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val seq: Int,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    val transcript: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * День (Р-18.1): ответ на вечерний вопрос «Что сегодня было самым главным?».
 *
 * Не заметка и живёт вне ленты — это память, а не дело: не разбирается на
 * пункты, не порождает возвратов, не правится и не удаляется свайпом. Ключ —
 * сама дата: день один, второго ответа за вечер не бывает.
 */
@Entity(tableName = "days")
data class DayEntity(
    /** ISO-дата «2026-08-21» — день, о котором рассказ, не момент записи. */
    @PrimaryKey val date: String,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    val transcript: String? = null,
    /**
     * Впечатление — строка для списка. Слова человека, сжатые моделью;
     * не сжалось — начало ответа как есть. Null — расшифровка ещё идёт.
     */
    val line: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Итог недели (Р-18.3): сводка впечатлений, собранная в воскресенье вечером.
 *
 * Хранится, а не считается на лету: итог собирается один раз и не
 * переписывается — иначе понедельничный взгляд менял бы воскресную память.
 */
@Entity(tableName = "week_recaps")
data class WeekRecapEntity(
    /** ISO-дата понедельника недели, за которую итог. */
    @PrimaryKey @ColumnInfo(name = "week_start") val weekStart: String,
    val text: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Человек, упомянутый в записях (Р-14.7). */
@Entity(tableName = "entities", indices = [Index(value = ["name_norm"], unique = true)])
data class PersonEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_norm") val nameNorm: String,
    /** `unknown` · `known` · `declined`. «Не надо» закрывает имя навсегда. */
    val status: String = PersonStatus.UNKNOWN.wire,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "seen_count") val seenCount: Int = 1,
    /** Что человек рассказал: одно слово в meta-строке пунктов («сестра»). */
    val fact: String? = null,
    /** Когда спрашивали в последний раз — чаще раза в три дня нельзя. */
    @ColumnInfo(name = "asked_at") val askedAt: Long? = null,
    /**
     * Куда склеен этот человек, если владелец подтвердил, что это одно лицо
     * (Д-30). Строка остаётся: «разные» тоже ответ, и переспрашивать его
     * продукт не вправе.
     */
    @ColumnInfo(name = "merged_into") val mergedInto: String? = null,
    /**
     * С кем этот человек **не** один и тот же (Д-30). Ответ «разные» тоже
     * ответ, и переспрашивать его продукт не вправе.
     */
    @ColumnInfo(name = "apart") val apart: String? = null,
)

/**
 * Кого упоминает запись (Д-26).
 *
 * Пара, а не счётчик: «три записи про Веру» надо не только посчитать, но и
 * показать. Пишется при разборе, когда модель уже назвала имена, — искать их
 * по тексту задним числом значит ловить «верну» вместо «Веры».
 */
@Entity(
    tableName = "person_notes",
    primaryKeys = ["person_id", "note_id"],
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("person_id"), Index("note_id")],
)
data class PersonNote(
    @ColumnInfo(name = "person_id") val personId: String,
    @ColumnInfo(name = "note_id") val noteId: String,
)

enum class PersonStatus(val wire: String) {
    UNKNOWN("unknown"), KNOWN("known"), DECLINED("declined");

    companion object {
        fun of(wire: String?): PersonStatus =
            entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/**
 * Связь между заметками (Р-15.11).
 *
 * Хранится **одной строкой на связь**, а не двумя: обе стороны читают одну и ту
 * же запись, просто с разных концов. Две строки означали бы, что связь можно
 * рассинхронизировать — снять с одной стороны и забыть о другой, — а такой
 * связи не бывает.
 *
 * Каскад по обоим концам: удалённая заметка не должна оставлять за собой
 * ссылку в никуда. Мягкое удаление каскада не вызывает, поэтому выборки
 * дополнительно отсеивают заметки с `deleted_at`.
 */
@Entity(
    tableName = "links",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["from_note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["to_note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("from_note_id"), Index("to_note_id")],
)
data class LinkEntity(
    @PrimaryKey val id: String,
    /** Новая заметка — та, при разборе которой связь нашлась. */
    @ColumnInfo(name = "from_note_id") val fromNoteId: String,
    @ColumnInfo(name = "to_note_id") val toNoteId: String,
    val reason: String,
    val confidence: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Чем одна заметка приходится другой. */
enum class LinkReason(val wire: String) {
    /** Продолжает начатое — самый частый случай. */
    CONTINUES("continues"),

    /** Отвечает на прозвучавший раньше вопрос. */
    ANSWERS("answers"),

    /** Спорит с прежним: человек передумал. */
    DISPUTES("disputes");

    companion object {
        fun of(wire: String?): LinkReason? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Вопрос интервьюера (Р-15.14).
 *
 * Хранится, а не держится в памяти экрана: без истории второй круг задаёт то
 * же самое другими словами, и «покрутить» превращается в допрос по кругу.
 */
@Entity(
    tableName = "questions",
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("note_id")],
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "note_id") val noteId: String,
    val text: String,
    @ColumnInfo(name = "asked_at") val askedAt: Long,
)
