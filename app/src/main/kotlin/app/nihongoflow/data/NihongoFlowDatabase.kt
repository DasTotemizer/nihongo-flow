package app.nihongoflow.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeckEntity::class,
        NoteEntity::class,
        CardEntity::class,
        MemoryStateEntity::class,
        ReviewEntity::class,
        DeferralEntity::class,
        RecoveryEntity::class,
        HolidayEntity::class,
        SettingsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NihongoFlowDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
    abstract fun progressDao(): ProgressDao

    companion object {
        const val FILE_NAME = "nihongo-flow.db"

        /**
         * Opens the database. There is deliberately no destructive-migration fallback: a
         * missing migration must fail loudly in testing rather than erase a learner's history.
         */
        fun open(context: Context, name: String = FILE_NAME): NihongoFlowDatabase =
            Room.databaseBuilder(context.applicationContext, NihongoFlowDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                .build()

        /** Version 2 adds the two-button answer setting, on by default. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN twoButtonReview INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
