package ai.prinim.prinyal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, ItemEntity::class, ReturnEntity::class, TopicEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class PrinyalDb : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun items(): ItemDao
    abstract fun returns(): ReturnDao
    abstract fun topics(): TopicDao

    companion object {
        @Volatile
        private var instance: PrinyalDb? = null

        fun get(context: Context): PrinyalDb =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** v1 → v2: мягкое удаление записей (спека R1.1 §2.2). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN deleted_at INTEGER DEFAULT NULL")
            }
        }

        /**
         * v2 → v3: разделы (scope 1.0.1 Р-14.1) и вид записи (Р-14.4).
         *
         * Одна миграция на всю версию, а не пять подряд по числу задач: база
         * живая, в ней дневник владельца, и каждый лишний шаг — лишний риск на
         * ней же.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS topics (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        name_norm TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        archived_at INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_topics_name_norm ON topics(name_norm)"
                )
                db.execSQL("ALTER TABLE notes ADD COLUMN topic_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE notes ADD COLUMN topic_source TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE notes ADD COLUMN note_kind TEXT DEFAULT NULL")
            }
        }

        private fun build(context: Context): PrinyalDb =
            Room.databaseBuilder(context, PrinyalDb::class.java, "prinyal.db")
                // Destructive-падения нет намеренно: dogfood-корпус терять нельзя.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** Только для тестов. */
        fun inMemory(context: Context): PrinyalDb =
            Room.inMemoryDatabaseBuilder(context, PrinyalDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
