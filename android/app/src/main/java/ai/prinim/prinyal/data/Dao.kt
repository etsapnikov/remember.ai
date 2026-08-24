package ai.prinim.prinyal.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class NoteWithItems(
    @Embedded val note: NoteEntity,
    @Relation(parentColumn = "id", entityColumn = "note_id")
    val items: List<ItemEntity>,
)

@Dao
interface NoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: String): NoteEntity?

    @Transaction
    @Query("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY created_at DESC")
    fun feed(): Flow<List<NoteWithItems>>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    fun watch(id: String): Flow<NoteWithItems?>

    /** Заметки одного раздела — экран раздела читается ровно как лента. */
    @Transaction
    @Query(
        "SELECT * FROM notes WHERE deleted_at IS NULL AND topic_id = :topicId " +
            "ORDER BY created_at DESC"
    )
    fun byTopic(topicId: String): Flow<List<NoteWithItems>>

    /**
     * Решения — хронология (Р-15.10).
     *
     * Раздел собирается запросом, а не хранится топиком: решение о релизе
     * по-прежнему относится к «Работе», и отбирать у заметки её раздел ради
     * второго списка значило бы платить структурой за навигацию.
     */
    @Transaction

    @Query("SELECT COUNT(*) FROM notes WHERE deleted_at IS NULL AND note_kind = 'decision'")
    fun decisionCount(): Flow<Int>

    /** Заметки, которые машина не смогла отнести и человек ещё не отнёс. */
    /** То же определение, что у счётчика: список и число обязаны сходиться. */
    @Transaction
    @Query(
        "SELECT * FROM notes WHERE deleted_at IS NULL AND topic_id IS NULL " +
            "AND transcript IS NOT NULL AND transcript != '' ORDER BY created_at DESC"
    )
    fun withoutTopic(): Flow<List<NoteWithItems>>

    /** Разобранные заметки без раздела — материал для ретро-прогона. */
    @Query(
        "SELECT * FROM notes WHERE deleted_at IS NULL AND topic_id IS NULL " +
            "AND transcript IS NOT NULL AND transcript != '' ORDER BY created_at DESC"
    )
    suspend fun looseList(): List<NoteEntity>

    @Query("UPDATE notes SET sibling_id = :siblingId WHERE id = :id")
    suspend fun setSibling(id: String, siblingId: String?)

    @Query("UPDATE notes SET topic_id = :topicId, topic_source = :source WHERE id = :id")
    suspend fun setTopic(id: String, topicId: String?, source: String)

    /** Что ждёт отправки: очередь переживает перезагрузку, потому что живёт в базе. */
    @Query(
        "SELECT * FROM notes WHERE deleted_at IS NULL AND status IN ('recorded', 'queued') " +
            "ORDER BY created_at ASC"
    )
    suspend fun pending(): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE deleted_at IS NULL AND status IN ('recorded', 'queued')")
    fun pendingCount(): Flow<Int>

    @Query("UPDATE notes SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE notes SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM notes WHERE deleted_at IS NULL ORDER BY created_at ASC")
    suspend fun all(): List<NoteEntity>

    @Query("UPDATE notes SET interview = :state WHERE id = :id")
    suspend fun setInterview(id: String, state: String)

    // --- мягкое удаление (спека R1.1 §2.2) ---

    @Query("UPDATE notes SET deleted_at = :at WHERE id = :id")
    suspend fun softDelete(id: String, at: Long)

    @Query("UPDATE notes SET deleted_at = NULL WHERE id = :id")
    suspend fun undelete(id: String)

    @Query("SELECT * FROM notes WHERE deleted_at IS NOT NULL")
    suspend fun softDeleted(): List<NoteEntity>

    /** Мусор для групповой уборки: записи без пунктов, разбор которых завершился. */
    @Query(
        "SELECT * FROM notes WHERE deleted_at IS NULL " +
            "AND status IN ('failed_asr', 'failed_llm') " +
            "AND id NOT IN (SELECT DISTINCT note_id FROM items)"
    )
    suspend fun junk(): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM notes WHERE deleted_at IS NULL")
    suspend fun countAlive(): Int
}

@Dao
interface ItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ItemEntity>)

    @Update
    suspend fun update(item: ItemEntity)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: String): ItemEntity?

    @Query("SELECT * FROM items WHERE note_id = :noteId ORDER BY position ASC")
    suspend fun forNote(noteId: String): List<ItemEntity>

    @Query("DELETE FROM items WHERE note_id = :noteId")
    suspend fun deleteForNote(noteId: String)

    @Query("UPDATE items SET state = :state WHERE id = :id")
    suspend fun setState(id: String, state: String)

    @Query("SELECT * FROM items ORDER BY id ASC")
    suspend fun all(): List<ItemEntity>

    @Query(
        "SELECT * FROM items WHERE state IN ('planned', 'snoozed') " +
            "AND due_kind != 'none' ORDER BY due_at ASC"
    )
    suspend fun scheduled(): List<ItemEntity>
}

@Dao
interface ReturnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReturnEntity)

    @Update
    suspend fun update(entity: ReturnEntity)

    @Query("SELECT * FROM returns WHERE id = :id")
    suspend fun byId(id: String): ReturnEntity?

    @Query("SELECT * FROM returns WHERE item_id = :itemId ORDER BY scheduled_at ASC")
    suspend fun forItem(itemId: String): List<ReturnEntity>

    /** Что должно было прозвенеть, но не прозвенело: перезагрузка, доза, убитый процесс. */
    @Query("SELECT * FROM returns WHERE fired_at IS NULL AND scheduled_at <= :now")
    suspend fun due(now: Long): List<ReturnEntity>

    @Query("SELECT * FROM returns WHERE fired_at IS NULL ORDER BY scheduled_at ASC")
    suspend fun upcoming(): List<ReturnEntity>

    @Query("DELETE FROM returns WHERE item_id = :itemId AND fired_at IS NULL")
    suspend fun dropPending(itemId: String)

    @Query("SELECT * FROM returns ORDER BY scheduled_at ASC")
    suspend fun all(): List<ReturnEntity>
}

/**
 * Разделы. Создание идёт только через [byNorm] + [insert]: «создать впрок»
 * в продукте нет, раздел появляется вместе с первой отнесённой к нему заметкой.
 */
@Dao
interface TopicDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TopicEntity)

    @Update
    suspend fun update(entity: TopicEntity)

    @Query("SELECT * FROM topics WHERE archived_at IS NULL ORDER BY name COLLATE NOCASE")
    suspend fun live(): List<TopicEntity>

    @Query("SELECT * FROM topics WHERE name_norm = :norm LIMIT 1")
    suspend fun byNorm(norm: String): TopicEntity?

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun byId(id: String): TopicEntity?

    @Query("SELECT COUNT(*) FROM topics WHERE archived_at IS NULL AND kind = 'auto'")
    suspend fun autoCount(): Int

    /**
     * Разделы с числом заметок и живых пунктов — то, что показывает экран.
     *
     * Пустые не попадают в выборку по INNER JOIN: раздела без заметок в
     * продукте не существует, показывать его было бы обещанием папки.
     *
     * Решения и факты в «живых» не считаются: они не дела и возвратов не
     * порождают, а в счётчике выглядели как невыполненное.
     */
    @Query(
        """
        SELECT t.id AS id, t.name AS name,
               COUNT(DISTINCT n.id) AS notes,
               COUNT(DISTINCT CASE WHEN i.state IN ('planned','returned','snoozed')
                                    AND i.type NOT IN ('decision','fact')
                                   THEN i.id END) AS liveItems,
               MAX(n.created_at) AS lastAt
        FROM topics t
        JOIN notes n ON n.topic_id = t.id AND n.deleted_at IS NULL
        LEFT JOIN items i ON i.note_id = n.id
        WHERE t.archived_at IS NULL
        GROUP BY t.id
        ORDER BY lastAt DESC
        """
    )
    fun overview(): Flow<List<TopicOverview>>

    /**
     * Сколько заметок осталось без раздела.
     *
     * Считаем только те, где есть что раскладывать. Записи, в которых ничего не
     * расслышано, разделу не принадлежат и принадлежать не могут — но раньше
     * они попадали сюда, а в настройки нет, и продукт называл два разных числа
     * одним словом: «14 заметок» в разделах против «7» в настройках в один и
     * тот же вечер (аудит Д-7, п. 8).
     *
     * Определение одно и то же с [NoteDao.looseList] — иначе расхождение
     * вернётся при первой же правке одного из запросов.
     */
    @Query(
        "SELECT COUNT(*) FROM notes WHERE topic_id IS NULL AND deleted_at IS NULL " +
            "AND transcript IS NOT NULL AND transcript != ''"
    )
    fun looseCount(): Flow<Int>
}

data class TopicOverview(
    val id: String,
    val name: String,
    val notes: Int,
    val liveItems: Int,
    val lastAt: Long,
)

@Dao
interface ReplacementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReplacementEntity)

    @Query("DELETE FROM replacements WHERE id = :id")
    suspend fun delete(id: String)

    /** От длинных фраз к коротким: иначе правило на слово съест фразу из трёх. */
    @Query("SELECT * FROM replacements ORDER BY LENGTH(from_norm) DESC")
    suspend fun all(): List<ReplacementEntity>

    @Query("SELECT * FROM replacements ORDER BY hits DESC, created_at DESC")
    fun watch(): Flow<List<ReplacementEntity>>

    @Query("SELECT * FROM replacements WHERE id = :id")
    suspend fun byId(id: String): ReplacementEntity?

    @Query("UPDATE replacements SET hits = hits + :times WHERE id = :id")
    suspend fun addHits(id: String, times: Int)
}

@Dao
interface PersonFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: PersonFact)

    @Query("SELECT * FROM person_facts WHERE person_id = :personId ORDER BY at ASC")
    suspend fun forPerson(personId: String): List<PersonFact>

    @Query("SELECT * FROM person_facts WHERE person_id = :personId ORDER BY at ASC")
    fun watch(personId: String): Flow<List<PersonFact>>

    @Query("SELECT * FROM person_facts")
    suspend fun all(): List<PersonFact>

    @Query("DELETE FROM person_facts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(DISTINCT person_id) FROM person_facts")
    suspend fun peopleWithFacts(): Int
}

@Dao
interface DayDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DayEntity)

    @Update
    suspend fun update(entity: DayEntity)

    @Query("SELECT * FROM days WHERE date = :date")
    suspend fun byDate(date: String): DayEntity?

    @Query("SELECT * FROM days ORDER BY date DESC")
    fun watch(): Flow<List<DayEntity>>

    @Query("SELECT * FROM days WHERE date >= :from AND date <= :to ORDER BY date ASC")
    suspend fun between(from: String, to: String): List<DayEntity>

    @Query("SELECT COUNT(*) FROM days WHERE date >= :from AND date <= :to")
    suspend fun countBetween(from: String, to: String): Int
}

@Dao
interface WeekRecapDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: WeekRecapEntity)

    @Query("SELECT * FROM week_recaps WHERE week_start = :weekStart")
    suspend fun byWeek(weekStart: String): WeekRecapEntity?

    @Query("SELECT * FROM week_recaps WHERE week_start = :weekStart")
    fun watch(weekStart: String): Flow<WeekRecapEntity?>
}

@Dao
interface SegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SegmentEntity)

    @Query("SELECT * FROM note_segments WHERE note_id = :noteId ORDER BY seq ASC")
    suspend fun forNote(noteId: String): List<SegmentEntity>

    @Query("SELECT * FROM note_segments WHERE note_id = :noteId ORDER BY seq ASC")
    fun watch(noteId: String): Flow<List<SegmentEntity>>

    @Query("SELECT COALESCE(MAX(seq), -1) + 1 FROM note_segments WHERE note_id = :noteId")
    suspend fun nextSeq(noteId: String): Int

    @Query("UPDATE note_segments SET transcript = :transcript WHERE id = :id")
    suspend fun setTranscript(id: String, transcript: String)
}

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: PersonEntity)

    @Update
    suspend fun update(entity: PersonEntity)

    @Query("SELECT * FROM entities WHERE name_norm = :norm LIMIT 1")
    suspend fun byNorm(norm: String): PersonEntity?

    @Query("SELECT * FROM entities WHERE id = :id")
    suspend fun byId(id: String): PersonEntity?

    @Query("UPDATE entities SET seen_count = seen_count + 1 WHERE id = :id")
    suspend fun sawAgain(id: String)

    /**
     * Кого стоит спросить: встречался дважды и больше, ничего о нём не знаем,
     * и от него не отказались.
     */
    @Query(
        "SELECT * FROM entities WHERE status = 'unknown' AND seen_count >= 2 " +
            "ORDER BY seen_count DESC, first_seen ASC LIMIT 1"
    )
    fun candidate(): Flow<PersonEntity?>

    @Query("SELECT * FROM entities WHERE status = 'known' AND fact IS NOT NULL")
    suspend fun known(): List<PersonEntity>

    /** Когда спрашивали в последний раз — по всем сущностям сразу. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(pair: PersonNote)

    @Query("UPDATE entities SET merged_into = :target WHERE id = :id")
    suspend fun mergeInto(id: String, target: String)

    @Query("UPDATE entities SET apart = :other WHERE id = :id")
    suspend fun keepApart(id: String, other: String)

    /**
     * Люди для раздела: только те, кого упоминают **две разные записи**, и
     * только несклеенные — склеенный живёт под именем того, в кого склеен.
     */
    @Query(
        "SELECT e.id AS id, e.name AS name, e.fact AS fact, " +
            "COUNT(DISTINCT pn.note_id) AS notes, " +
            "MIN(n.created_at) AS firstAt, MAX(n.created_at) AS lastAt " +
            "FROM entities e " +
            "JOIN person_notes pn ON pn.person_id = e.id " +
            "JOIN notes n ON n.id = pn.note_id AND n.deleted_at IS NULL " +
            "WHERE e.merged_into IS NULL " +
            "GROUP BY e.id " +
            // Порог уточнён (макет 13b): две разные записи **или** факт из
            // речи. Факт — то самое содержимое, ради которого карточка и
            // существует: узнав «у Веры ключи от дачи», прятать Веру до
            // второй записи глупо.
            "HAVING notes >= :minNotes " +
            "OR EXISTS(SELECT 1 FROM person_facts pf WHERE pf.person_id = e.id) " +
            "ORDER BY lastAt DESC"
    )
    fun people(minNotes: Int): Flow<List<PersonOverview>>

    @Query("SELECT * FROM entities WHERE merged_into IS NULL")
    suspend fun allLive(): List<PersonEntity>

    @Query("SELECT * FROM entities WHERE merged_into IS NULL AND apart IS NULL")
    fun watchLive(): Flow<List<PersonEntity>>

    /** Записи, где упомянут человек, — свежие впереди. */
    @Transaction
    @Query(
        "SELECT n.* FROM notes n JOIN person_notes pn ON pn.note_id = n.id " +
            "WHERE pn.person_id = :personId AND n.deleted_at IS NULL " +
            "ORDER BY n.created_at DESC"
    )
    fun notesOf(personId: String): Flow<List<NoteWithItems>>

    @Query(
        "SELECT n.* FROM notes n JOIN person_notes pn ON pn.note_id = n.id " +
            "WHERE pn.person_id = :personId AND n.deleted_at IS NULL " +
            "ORDER BY n.created_at ASC"
    )
    suspend fun notesOfOnce(personId: String): List<NoteEntity>

    @Query("SELECT MAX(asked_at) FROM entities")
    suspend fun lastAskedAt(): Long?
}

/** Заметка на другом конце связи — всё, что нужно строке блока «Связано». */
data class LinkedNote(
    val id: String,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val transcript: String?,
)

@Dao
interface LinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<LinkEntity>)

    @Query("DELETE FROM links WHERE from_note_id = :noteId")
    suspend fun dropFrom(noteId: String)

    /**
     * Обе стороны одной связи.
     *
     * `UNION ALL` вместо двух запросов: связь хранится одной строкой, и заметке
     * всё равно, с какого конца она в ней записана. Удалённые мягко —
     * отсеиваются здесь: каскад до них не доходит, пока живёт снекбар.
     */
    @Query(
        "SELECT n.id AS id, l.reason AS reason, n.created_at AS created_at, " +
            "n.transcript AS transcript FROM links l " +
            "JOIN notes n ON n.id = l.to_note_id " +
            "WHERE l.from_note_id = :noteId AND n.deleted_at IS NULL " +
            "UNION ALL " +
            "SELECT n.id AS id, l.reason AS reason, n.created_at AS created_at, " +
            "n.transcript AS transcript FROM links l " +
            "JOIN notes n ON n.id = l.from_note_id " +
            "WHERE l.to_note_id = :noteId AND n.deleted_at IS NULL " +
            "ORDER BY created_at DESC"
    )
    fun forNote(noteId: String): Flow<List<LinkedNote>>

    @Query("SELECT COUNT(*) FROM links")
    suspend fun count(): Int

    /** Все связи парами — сырьё для поиска кластеров (Р-15.12). */
    @Query("SELECT * FROM links")
    suspend fun pairs(): List<LinkEntity>
}

@Dao
interface QuestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuestionEntity)

    @Query("SELECT * FROM questions WHERE note_id = :noteId ORDER BY asked_at ASC")
    suspend fun forNote(noteId: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE note_id = :noteId ORDER BY asked_at ASC")
    fun watch(noteId: String): Flow<List<QuestionEntity>>

    /**
     * Последний заданный вопрос — источник правды для экрана.
     *
     * Вопрос жил в памяти вьюмодели, и это была вторая половина поломки петли:
     * экран записи убивает задачу, вьюмодель пересоздаётся, вопрос исчезал. С
     * ответом человек возвращался к кнопке «Покрутить идею», будто ничего не
     * было.
     */
    @Query("SELECT * FROM questions WHERE note_id = :noteId ORDER BY asked_at DESC LIMIT 1")
    fun watchLast(noteId: String): Flow<QuestionEntity?>
}


/** Человек в списке: имя, факт и сколько записей его упоминают (Д-26). */
data class PersonOverview(
    val id: String,
    val name: String,
    val fact: String?,
    val notes: Int,
    val firstAt: Long,
    val lastAt: Long,
)
