package ai.prinim.prinyal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NoteEntity::class, ItemEntity::class, ReturnEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PrinyalDb : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun items(): ItemDao
    abstract fun returns(): ReturnDao

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

        private fun build(context: Context): PrinyalDb =
            Room.databaseBuilder(context, PrinyalDb::class.java, "prinyal.db")
                // Destructive-падения нет намеренно: dogfood-корпус терять нельзя.
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Только для тестов. */
        fun inMemory(context: Context): PrinyalDb =
            Room.inMemoryDatabaseBuilder(context, PrinyalDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
