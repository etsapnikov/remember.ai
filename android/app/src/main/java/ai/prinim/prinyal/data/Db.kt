package ai.prinim.prinyal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [NoteEntity::class, ItemEntity::class, ReturnEntity::class],
    version = 1,
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

        private fun build(context: Context): PrinyalDb =
            Room.databaseBuilder(context, PrinyalDb::class.java, "prinyal.db")
                // Миграций пока нет — версия первая. Destructive-падение здесь было бы
                // потерей dogfood-корпуса, поэтому его нет тоже: сломается — увидим.
                .build()

        /** Только для тестов. */
        fun inMemory(context: Context): PrinyalDb =
            Room.inMemoryDatabaseBuilder(context, PrinyalDb::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
