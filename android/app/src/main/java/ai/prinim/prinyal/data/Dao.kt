package ai.prinim.prinyal.data

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
