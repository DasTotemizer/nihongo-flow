package app.nihongoflow.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema version 1. Times are epoch milliseconds (UTC), dates are ISO-8601 strings and
// enums are stored by name, so the database reads the same in any time zone.
//
// Memory state, review history and presentation bookkeeping live in separate tables, as
// the architecture requires: only a real review writes memory_states.

@Entity(tableName = "decks")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deck_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("deck_id"), Index("new_position")],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "deck_id") val deckId: Long,
    val front: String,
    val back: String,
    val reading: String,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "new_position") val newPosition: Long,
    val suspended: Boolean,
)

/** The columns of one FSRS memory state, reused for history snapshots. */
data class MemoryColumns(
    val phase: String,
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val due: Long,
    @ColumnInfo(name = "last_review") val lastReview: Long?,
)

@Entity(
    tableName = "memory_states",
    foreignKeys = [
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["card_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("due")],
)
data class MemoryStateEntity(
    @PrimaryKey @ColumnInfo(name = "card_id") val cardId: Long,
    @Embedded val memory: MemoryColumns,
)

/** Append-only. Rows are never updated. */
@Entity(
    tableName = "review_log",
    foreignKeys = [
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["card_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("card_id"), Index("reviewed_at")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "card_id") val cardId: Long,
    val rating: String,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    @ColumnInfo(name = "duration_ms") val durationMillis: Long?,
    val source: String,
    @Embedded(prefix = "before_") val before: MemoryColumns,
    @Embedded(prefix = "after_") val after: MemoryColumns,
)

@Entity(
    tableName = "deferral_states",
    foreignKeys = [
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["card_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class DeferralEntity(
    @PrimaryKey @ColumnInfo(name = "card_id") val cardId: Long,
    @ColumnInfo(name = "first_deferred_on") val firstDeferredOn: String,
    @ColumnInfo(name = "last_deferred_on") val lastDeferredOn: String,
    @ColumnInfo(name = "study_days_deferred") val studyDaysDeferred: Int,
)

/** At most one row (id = 1). */
@Entity(tableName = "recovery_state")
data class RecoveryEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "started_on") val startedOn: String,
    @ColumnInfo(name = "backlog_at_start") val backlogAtStart: Int,
    @ColumnInfo(name = "ease_in") val easeIn: Boolean,
    @ColumnInfo(name = "study_days_completed") val studyDaysCompleted: Int,
    @ColumnInfo(name = "last_counted_study_day") val lastCountedStudyDay: String?,
)

@Entity(tableName = "holidays")
data class HolidayEntity(
    @PrimaryKey val id: String,
    val mode: String,
    @ColumnInfo(name = "starts_on") val startsOn: String,
    @ColumnInfo(name = "ends_on") val endsOn: String?,
)

/** Result row of the My Decks query. */
data class DeckSummaryRow(
    @Embedded val deck: DeckEntity,
    @ColumnInfo(name = "card_count") val cardCount: Int,
    @ColumnInfo(name = "new_count") val newCount: Int,
    @ColumnInfo(name = "due_count") val dueCount: Int,
)
