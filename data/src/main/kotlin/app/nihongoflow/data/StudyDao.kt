package app.nihongoflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface StudyDao {

    // Decks

    @Insert
    suspend fun insertDeck(deck: DeckEntity): Long

    @Query("UPDATE decks SET name = :name, description = :description WHERE id = :id")
    suspend fun renameDeck(id: Long, name: String, description: String)

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun deck(id: Long): DeckEntity?

    @Query("SELECT * FROM decks ORDER BY name COLLATE NOCASE, id")
    suspend fun decks(): List<DeckEntity>

    @Query(
        """
        SELECT d.*,
          COUNT(c.id) AS card_count,
          COALESCE(SUM(CASE WHEN c.suspended = 0 AND m.phase = 'NEW' THEN 1 ELSE 0 END), 0) AS new_count,
          COALESCE(SUM(CASE WHEN c.suspended = 0 AND m.phase != 'NEW' AND m.due < :dueBefore THEN 1 ELSE 0 END), 0) AS due_count
        FROM decks d
        LEFT JOIN cards c ON c.deck_id = d.id
        LEFT JOIN memory_states m ON m.card_id = c.id
        GROUP BY d.id
        ORDER BY d.name COLLATE NOCASE, d.id
        """,
    )
    suspend fun deckSummaries(dueBefore: Long): List<DeckSummaryRow>

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteDeckRow(id: Long)

    @Query("SELECT id FROM cards WHERE deck_id = :deckId")
    suspend fun cardIdsInDeck(deckId: Long): List<Long>

    /** Deletes children explicitly as well, so nothing depends on SQLite's foreign-key setting. */
    @Transaction
    suspend fun deleteDeck(id: Long) {
        cardIdsInDeck(id).forEach { deleteCard(it) }
        deleteDeckRow(id)
    }

    // Cards

    @Query("SELECT COALESCE(MAX(new_position), 0) FROM cards")
    suspend fun maxNewPosition(): Long

    @Insert
    suspend fun insertCardRow(card: CardEntity): Long

    @Upsert
    suspend fun upsertMemory(state: MemoryStateEntity)

    @Transaction
    suspend fun insertCard(card: CardEntity, initialMemory: MemoryColumns): Long {
        val id = insertCardRow(card.copy(newPosition = maxNewPosition() + 1))
        upsertMemory(MemoryStateEntity(id, initialMemory))
        return id
    }

    @Query("UPDATE cards SET front = :front, back = :back, reading = :reading, notes = :notes WHERE id = :id")
    suspend fun updateCardContent(id: Long, front: String, back: String, reading: String, notes: String)

    @Query("UPDATE cards SET suspended = :suspended WHERE id = :id")
    suspend fun setSuspended(id: Long, suspended: Boolean)

    @Query("DELETE FROM review_log WHERE card_id = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM deferral_states WHERE card_id = :id")
    suspend fun deleteDeferral(id: Long)

    @Query("DELETE FROM memory_states WHERE card_id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun deleteCardRow(id: Long)

    @Transaction
    suspend fun deleteCard(id: Long) {
        deleteHistory(id)
        deleteDeferral(id)
        deleteMemory(id)
        deleteCardRow(id)
    }

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun card(id: Long): CardEntity?

    @Query("SELECT * FROM cards WHERE deck_id = :deckId ORDER BY new_position")
    suspend fun cards(deckId: Long): List<CardEntity>

    @Query("SELECT * FROM memory_states WHERE card_id = :id")
    suspend fun memoryState(id: Long): MemoryStateEntity?

    @Query(
        """
        SELECT m.* FROM memory_states m JOIN cards c ON c.id = m.card_id
        WHERE c.suspended = 0 AND m.phase != 'NEW' AND m.due < :before
        ORDER BY m.due, m.card_id
        """,
    )
    suspend fun dueCards(before: Long): List<MemoryStateEntity>

    @Query(
        """
        SELECT c.id FROM cards c JOIN memory_states m ON m.card_id = c.id
        WHERE c.suspended = 0 AND m.phase = 'NEW'
        ORDER BY c.new_position LIMIT :limit
        """,
    )
    suspend fun newCardIds(limit: Int): List<Long>

    @Query(
        """
        SELECT m.due FROM memory_states m JOIN cards c ON c.id = m.card_id
        WHERE c.suspended = 0 AND m.phase != 'NEW' AND m.due >= :from AND m.due < :to
        """,
    )
    suspend fun dueTimesBetween(from: Long, to: Long): List<Long>

    @Query("SELECT m.* FROM memory_states m JOIN cards c ON c.id = m.card_id WHERE c.suspended = 0")
    suspend fun activeMemoryStates(): List<MemoryStateEntity>

    // Reviews

    @Insert
    suspend fun insertReview(log: ReviewLogEntity): Long

    @Transaction
    suspend fun applyReview(log: ReviewLogEntity, after: MemoryStateEntity) {
        insertReview(log)
        upsertMemory(after)
        deleteDeferral(log.cardId)
    }

    @Query("SELECT * FROM review_log WHERE reviewed_at >= :from ORDER BY reviewed_at, id")
    suspend fun reviewsSince(from: Long): List<ReviewLogEntity>

    @Query("SELECT MAX(reviewed_at) FROM review_log")
    suspend fun lastReviewTime(): Long?

    @Query(
        """
        SELECT duration_ms FROM review_log WHERE duration_ms IS NOT NULL
        ORDER BY reviewed_at DESC, id DESC LIMIT :limit
        """,
    )
    suspend fun recentDurations(limit: Int): List<Long>

    // Bookkeeping

    @Query("SELECT * FROM deferral_states WHERE card_id IN (:ids)")
    suspend fun deferrals(ids: List<Long>): List<DeferralEntity>

    @Upsert
    suspend fun upsertDeferrals(rows: List<DeferralEntity>)

    @Query("SELECT * FROM recovery_state WHERE id = 1")
    suspend fun recovery(): RecoveryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecovery(row: RecoveryEntity)

    @Query("DELETE FROM recovery_state")
    suspend fun clearRecovery()

    @Query("SELECT * FROM holidays ORDER BY starts_on")
    suspend fun holidays(): List<HolidayEntity>

    @Query("DELETE FROM holidays")
    suspend fun clearHolidays()

    @Insert
    suspend fun insertHolidays(rows: List<HolidayEntity>)

    @Transaction
    suspend fun replaceHolidays(rows: List<HolidayEntity>) {
        clearHolidays()
        insertHolidays(rows)
    }
}
