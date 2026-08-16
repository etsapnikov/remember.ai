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
    BUY("buy"), DO("do"), TELL("tell"), DATE("date"), THOUGHT("thought"), FACT("fact");

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
    NONE("none"), LLM("llm"), USER("user");

    companion object {
        fun of(wire: String?): TopicSource =
            entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/** Что за запись — для формы карточки и будущих жанров (scope 1.0.1 Р-14.4). */
enum class NoteKind(val wire: String) {
    TASKS("tasks"), IDEA("idea"), QUESTION("question"), FACTS("facts"), MIXED("mixed");

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
