package ai.prinim.prinyal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NoteEntity::class,
        ItemEntity::class,
        ReturnEntity::class,
        TopicEntity::class,
        ReplacementEntity::class,
        SegmentEntity::class,
        PersonEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class PrinyalDb : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun items(): ItemDao
    abstract fun returns(): ReturnDao
    abstract fun topics(): TopicDao
    abstract fun replacements(): ReplacementDao
    abstract fun segments(): SegmentDao
    abstract fun people(): PersonDao

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

        /**
         * v3 → v4: словарь автозамен, сегменты записи, люди и тело в markdown.
         *
         * Снова одной миграцией на остаток версии 1.0.1 — по той же причине, что
         * и 2→3: база живая, и каждый лишний шаг это лишний риск на дневнике
         * владельца, а не на пустой схеме.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS replacements (
                        id TEXT NOT NULL PRIMARY KEY,
                        from_phrase TEXT NOT NULL,
                        from_norm TEXT NOT NULL,
                        to_phrase TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        hits INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_replacements_from_norm " +
                        "ON replacements(from_norm)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_segments (
                        id TEXT NOT NULL PRIMARY KEY,
                        note_id TEXT NOT NULL,
                        seq INTEGER NOT NULL,
                        audio_path TEXT NOT NULL,
                        transcript TEXT,
                        created_at INTEGER NOT NULL,
                        FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_note_segments_note_id " +
                        "ON note_segments(note_id)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS entities (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        name_norm TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'unknown',
                        first_seen INTEGER NOT NULL,
                        seen_count INTEGER NOT NULL DEFAULT 1,
                        fact TEXT,
                        asked_at INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_entities_name_norm " +
                        "ON entities(name_norm)"
                )
                db.execSQL("ALTER TABLE notes ADD COLUMN body_md TEXT DEFAULT NULL")

                // Первый сегмент для уже существующих записей: без него карточка
                // старой заметки осталась бы без аудио, когда плеер переедет на
                // сегменты.
                db.execSQL(
                    """
                    INSERT INTO note_segments (id, note_id, seq, audio_path, transcript, created_at)
                    SELECT id, id, 0, audio_path, transcript, created_at FROM notes
                    """.trimIndent()
                )
            }
        }

        /**
         * v4 → v5: вычистить строку «null», записанную в тело заметки.
         *
         * Прежняя сборка клала в `body_md` результат `optString`, а он на
         * значении JSON `null` возвращает **строку «null»**, а не пустоту.
         * В карточке из-за этого появлялся блок «Собрано» со словом null.
         * Код починен, но записанное в базу этим не исправляется — отсюда
         * миграция.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE notes SET body_md = NULL " +
                        "WHERE body_md IS NOT NULL AND (TRIM(body_md) = '' OR LOWER(TRIM(body_md)) = 'null')"
                )
            }
        }

        private fun build(context: Context): PrinyalDb =
            Room.databaseBuilder(context, PrinyalDb::class.java, "prinyal.db")
                // Destructive-падения нет намеренно: dogfood-корпус терять нельзя.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        /** Только для тестов. */
        fun inMemory(context: Context): PrinyalDb =
            Room.inMemoryDatabaseBuilder(context, PrinyalDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
