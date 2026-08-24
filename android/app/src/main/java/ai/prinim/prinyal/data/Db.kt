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
        LinkEntity::class,
        QuestionEntity::class,
        PersonNote::class,
        DayEntity::class,
        WeekRecapEntity::class,
        PersonFact::class,
    ],
    version = 18,
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
    abstract fun links(): LinkDao
    abstract fun questions(): QuestionDao
    abstract fun days(): DayDao
    abstract fun weekRecaps(): WeekRecapDao
    abstract fun personFacts(): PersonFactDao

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

        /** v5 → v6: связь половин разделённой записи (Р-15.5). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN sibling_id TEXT DEFAULT NULL")
            }
        }

        /** v6 → v7: связи между заметками (Р-15.11). */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS links (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "from_note_id TEXT NOT NULL, " +
                        "to_note_id TEXT NOT NULL, " +
                        "reason TEXT NOT NULL, " +
                        "confidence TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL, " +
                        "FOREIGN KEY(from_note_id) REFERENCES notes(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(to_note_id) REFERENCES notes(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_links_from_note_id ON links(from_note_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_links_to_note_id ON links(to_note_id)")
            }
        }

        /** v7 → v8: вопросы интервьюера (Р-15.14). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS questions (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "note_id TEXT NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "asked_at INTEGER NOT NULL, " +
                        "FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_questions_note_id ON questions(note_id)"
                )
            }
        }

        /** v8 → v9: прежняя формулировка пункта (Р-15.8). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN previous_text TEXT DEFAULT NULL")
            }
        }

        /** v9 → v10: кого упоминает запись и склейка имён (Д-26, Д-30). */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entities ADD COLUMN merged_into TEXT DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS person_notes (" +
                        "person_id TEXT NOT NULL, note_id TEXT NOT NULL, " +
                        "PRIMARY KEY(person_id, note_id), " +
                        "FOREIGN KEY(person_id) REFERENCES entities(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(note_id) REFERENCES notes(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_person_notes_person_id ON person_notes(person_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_person_notes_note_id ON person_notes(note_id)")
            }
        }

        /** v10 → v11: ответ «разные» по паре однофамильцев (Д-30). */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE entities ADD COLUMN apart TEXT DEFAULT NULL")
            }
        }

        /** v11 → v12: повторяющиеся напоминания (Р-16.2). */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN repeat_rule TEXT DEFAULT NULL")
            }
        }

        /** v12 → v13: сделанный повтор — это «вернусь», и он несёт две даты. */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN repeat_done_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE items ADD COLUMN repeat_next_at INTEGER DEFAULT NULL")
            }
        }

        /** v13 → v14: «Дни» (Р-18.1), итог недели (Р-18.3), «В план» (Р-18.4). */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS days (" +
                        "date TEXT NOT NULL PRIMARY KEY, " +
                        "audio_path TEXT NOT NULL, " +
                        "transcript TEXT, line TEXT, " +
                        "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS week_recaps (" +
                        "week_start TEXT NOT NULL PRIMARY KEY, " +
                        "text TEXT NOT NULL, " +
                        "created_at INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE items ADD COLUMN revived_at INTEGER DEFAULT NULL")
            }
        }

        /** v14 → v15: стадия разговора об идее (Р-19.1). */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN interview TEXT NOT NULL DEFAULT 'none'")
            }
        }

        /** v15 → v16: факты о людях отдельными строками (Р-20.1). */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS person_facts (" +
                        "id TEXT NOT NULL PRIMARY KEY, person_id TEXT NOT NULL, " +
                        "text TEXT NOT NULL, at INTEGER NOT NULL, " +
                        "FOREIGN KEY(person_id) REFERENCES entities(id) ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_person_facts_person_id " +
                        "ON person_facts(person_id)"
                )
                // Прежний единственный факт переезжает первой строкой: знание,
                // добытое доспросом, терять нельзя.
                db.execSQL(
                    "INSERT INTO person_facts (id, person_id, text, at) " +
                        "SELECT id, id, fact, first_seen FROM entities " +
                        "WHERE fact IS NOT NULL AND fact != ''"
                )
            }
        }

        /** v16 → v17: пункт, выросший из разговора (Р-20.2). */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE items ADD COLUMN from_interview INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v17 → v18: пары «вопрос — ответ» и резюме разговора (Р-23.2). */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN answer TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE questions ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE questions ADD COLUMN slot TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE questions ADD COLUMN fact_role TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE notes ADD COLUMN spin_summary TEXT DEFAULT NULL")
            }
        }

        private fun build(context: Context): PrinyalDb =
            Room.databaseBuilder(context, PrinyalDb::class.java, "prinyal.db")
                // Destructive-падения нет намеренно: dogfood-корпус терять нельзя.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18)
                .build()

        /** Только для тестов. */
        fun inMemory(context: Context): PrinyalDb =
            Room.inMemoryDatabaseBuilder(context, PrinyalDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
