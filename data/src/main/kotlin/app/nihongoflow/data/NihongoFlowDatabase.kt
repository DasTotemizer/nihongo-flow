package app.nihongoflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeckEntity::class,
        CardEntity::class,
        MemoryStateEntity::class,
        ReviewLogEntity::class,
        DeferralEntity::class,
        RecoveryEntity::class,
        HolidayEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NihongoFlowDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao

    companion object {
        const val FILE_NAME = "nihongo-flow.db"

        private val enableForeignKeys = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        /**
         * The app's database. Future schema changes must add a Migration here (never
         * fallbackToDestructiveMigration: learners' history must survive every update).
         */
        fun open(context: Context): NihongoFlowDatabase =
            Room.databaseBuilder(context.applicationContext, NihongoFlowDatabase::class.java, FILE_NAME)
                .addCallback(enableForeignKeys)
                .build()

        fun inMemory(context: Context): NihongoFlowDatabase =
            Room.inMemoryDatabaseBuilder(context, NihongoFlowDatabase::class.java)
                .addCallback(enableForeignKeys)
                .allowMainThreadQueries()
                .build()
    }
}
