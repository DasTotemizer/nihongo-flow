package app.nihongoflow.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room schema, version 1. Memory (FSRS) state, review history and workload presentation
// state live in separate tables, as the spec requires. Times are epoch milliseconds (UTC);
// calendar days are ISO dates (yyyy-mm-dd).

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val parentId: String?,
    val sourceKind: String,
    val sourceName: String,
    val sourceAuthor: String?,
    val sourceLicence: String?,
    val sourceUrl: String?,
    val createdAt: Long,
)

@Entity(tableName = "notes", indices = [Index("deckId")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val deckId: String,
    val kind: String,
    /** Field name → value, as a JSON object. */
    val fieldsJson: String,
    /** Tags as a JSON array. */
    val tagsJson: String,
    val sourceKind: String,
    val sourceName: String,
    val sourceAuthor: String?,
    val sourceLicence: String?,
    val sourceUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "cards", indices = [Index("noteId"), Index("deckId"), Index("newPosition")])
data class CardEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val deckId: String,
    val template: String,
    val clozeNumber: Int,
    val createdAt: Long,
    val newPosition: Long,
    val suspended: Boolean,
)

/** FSRS memory state per card. Only the memory scheduler's output is ever written here. */
@Entity(tableName = "fsrs_card_state", indices = [Index("due")])
data class MemoryStateEntity(
    @PrimaryKey val cardId: String,
    val phase: String,
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val due: Long,
    val lastReview: Long?,
)

/** Append-only review history. Rows are never updated. */
@Entity(tableName = "review_history", indices = [Index("cardId"), Index("reviewedAt")])
data class ReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: String,
    val rating: String,
    val reviewedAt: Long,
    val durationMillis: Long?,
    val source: String,
    val beforePhase: String,
    val beforeStep: Int?,
    val beforeStability: Double?,
    val beforeDifficulty: Double?,
    val beforeDue: Long,
    val beforeLastReview: Long?,
    val afterPhase: String,
    val afterStep: Int?,
    val afterStability: Double?,
    val afterDifficulty: Double?,
    val afterDue: Long,
    val afterLastReview: Long?,
)

/** Workload presentation bookkeeping: how long a due card has been deferred. */
@Entity(tableName = "workload_deferral")
data class DeferralEntity(
    @PrimaryKey val cardId: String,
    val firstDeferredOn: String,
    val lastDeferredOn: String,
    val studyDaysDeferred: Int,
)

/** Single-row table: the gradual-return state, if a recovery is in progress. */
@Entity(tableName = "workload_recovery_state")
data class RecoveryEntity(
    @PrimaryKey val id: Int = 1,
    val startedOn: String,
    val backlogAtStart: Int,
    val easeIn: Boolean,
    val studyDaysCompleted: Int,
    val lastCountedStudyDay: String?,
)

@Entity(tableName = "holiday_schedule")
data class HolidayEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val startsOn: String,
    val endsOn: String?,
)

/** Single-row table of study settings. */
@Entity(tableName = "user_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val desiredRetention: Double,
    val targetSessionMinutes: Int,
    val maxSessionMinutes: Int,
    val maxReviewsPerSession: Int,
    val newCardsPerDay: Int,
    val recoveryPacing: String,
    /** Comma-separated ISO day numbers, 1 = Monday. */
    val preferredStudyDays: String,
    val casualSessionMinutes: Int,
    val dayStartHour: Int,
    /** Comma-separated ReviewDirection names. */
    val enabledDirections: String,
    /** Added in schema version 2. */
    @ColumnInfo(defaultValue = "1") val twoButtonReview: Boolean = true,
)
