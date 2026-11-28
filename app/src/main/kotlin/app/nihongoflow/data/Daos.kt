package app.nihongoflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CollectionDao {
    @Query("SELECT * FROM decks ORDER BY name COLLATE NOCASE")
    suspend fun decks(): List<DeckEntity>

    @Query("SELECT * FROM decks WHERE id = :id")
    suspend fun deck(id: String): DeckEntity?

    @Upsert
    suspend fun upsertDeck(deck: DeckEntity)

    @Upsert
    suspend fun upsertDecks(decks: List<DeckEntity>)

    @Upsert
    suspend fun upsertNotes(notes: List<NoteEntity>)

    @Query("DELETE FROM decks WHERE id = :id")
    suspend fun deleteDeckRow(id: String)

    @Query("SELECT * FROM notes WHERE deckId = :deckId ORDER BY createdAt")
    suspend fun notes(deckId: String): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Query("SELECT * FROM notes")
    suspend fun allNotes(): List<NoteEntity>

    @Query("SELECT id FROM notes WHERE deckId = :deckId")
    suspend fun noteIds(deckId: String): List<String>

    @Upsert
    suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteRow(id: String)

    @Query("SELECT * FROM cards WHERE noteId = :noteId")
    suspend fun cards(noteId: String): List<CardEntity>

    @Query("SELECT id FROM cards WHERE noteId = :noteId")
    suspend fun cardIds(noteId: String): List<String>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun card(id: String): CardEntity?

    @Query("SELECT * FROM cards WHERE id IN (:ids)")
    suspend fun cardsByIds(ids: List<String>): List<CardEntity>

    @Query("SELECT COUNT(*) FROM cards WHERE deckId = :deckId AND suspended = 0")
    suspend fun cardCount(deckId: String): Int

    @Query("SELECT COALESCE(MAX(newPosition), 0) FROM cards")
    suspend fun maxNewPosition(): Long

    @Upsert
    suspend fun upsertCards(cards: List<CardEntity>)

    @Query("DELETE FROM cards WHERE noteId = :noteId")
    suspend fun deleteCardsOfNote(noteId: String)

    @Query(
        """
        SELECT c.* FROM cards c LEFT JOIN fsrs_card_state m ON m.cardId = c.id
        WHERE c.suspended = 0 AND (m.cardId IS NULL OR m.phase = 'NEW')
        ORDER BY c.newPosition LIMIT :limit
        """,
    )
    suspend fun newCards(limit: Int): List<CardEntity>
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM fsrs_card_state WHERE cardId = :cardId")
    suspend fun memory(cardId: String): MemoryStateEntity?

    @Query("SELECT * FROM fsrs_card_state")
    suspend fun allMemory(): List<MemoryStateEntity>

    @Query(
        """
        SELECT m.* FROM fsrs_card_state m JOIN cards c ON c.id = m.cardId
        WHERE c.suspended = 0 AND m.phase != 'NEW' AND m.due < :before
        """,
    )
    suspend fun dueMemory(before: Long): List<MemoryStateEntity>

    @Query(
        """
        SELECT COUNT(*) FROM fsrs_card_state m JOIN cards c ON c.id = m.cardId
        WHERE c.suspended = 0 AND m.phase != 'NEW' AND m.due >= :from AND m.due < :until
        """,
    )
    suspend fun countDue(from: Long, until: Long): Int

    @Upsert
    suspend fun upsertMemory(state: MemoryStateEntity)

    @Upsert
    suspend fun upsertMemories(states: List<MemoryStateEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReviews(reviews: List<ReviewEntity>)

    @Query("DELETE FROM fsrs_card_state WHERE cardId IN (:ids)")
    suspend fun deleteMemory(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReview(review: ReviewEntity)

    @Query("SELECT * FROM review_history WHERE reviewedAt >= :since ORDER BY reviewedAt, id")
    suspend fun reviewsSince(since: Long): List<ReviewEntity>

    @Query("SELECT * FROM review_history WHERE cardId = :cardId ORDER BY reviewedAt, id")
    suspend fun reviewsFor(cardId: String): List<ReviewEntity>

    @Query("SELECT MAX(reviewedAt) FROM review_history")
    suspend fun lastReviewTime(): Long?

    @Query("SELECT COUNT(*) FROM review_history")
    suspend fun reviewCount(): Int

    @Query("DELETE FROM review_history WHERE cardId IN (:ids)")
    suspend fun deleteReviews(ids: List<String>)

    @Query("SELECT * FROM workload_deferral")
    suspend fun deferrals(): List<DeferralEntity>

    @Query("SELECT * FROM workload_deferral WHERE cardId IN (:ids)")
    suspend fun deferralsFor(ids: List<String>): List<DeferralEntity>

    @Query("DELETE FROM workload_deferral")
    suspend fun clearDeferrals()

    @Query("DELETE FROM workload_deferral WHERE cardId IN (:ids)")
    suspend fun deleteDeferrals(ids: List<String>)

    @Upsert
    suspend fun upsertDeferrals(states: List<DeferralEntity>)

    @Query("SELECT * FROM workload_recovery_state WHERE id = 1")
    suspend fun recovery(): RecoveryEntity?

    @Upsert
    suspend fun upsertRecovery(state: RecoveryEntity)

    @Query("DELETE FROM workload_recovery_state")
    suspend fun clearRecovery()

    @Query("SELECT * FROM holiday_schedule ORDER BY startsOn")
    suspend fun holidays(): List<HolidayEntity>

    @Query("DELETE FROM holiday_schedule")
    suspend fun clearHolidays()

    @Upsert
    suspend fun upsertHolidays(periods: List<HolidayEntity>)

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun settings(): SettingsEntity?

    @Upsert
    suspend fun upsertSettings(settings: SettingsEntity)
}
