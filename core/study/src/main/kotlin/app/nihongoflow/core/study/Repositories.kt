package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.workload.DeferralState
import app.nihongoflow.core.workload.RecoveryState
import kotlinx.datetime.Instant

/** Decks, notes and cards. Implemented by Room on Android and in memory for tests. */
interface CollectionRepository {
    suspend fun decks(): List<Deck>
    suspend fun deck(id: String): Deck?
    suspend fun saveDeck(deck: Deck)

    /** Deletes a deck with its notes, cards, memory and history. Only on explicit request. */
    suspend fun deleteDeck(id: String)

    suspend fun notes(deckId: String): List<Note>
    suspend fun note(id: String): Note?
    suspend fun allNotes(): List<Note>

    /** Saves a note and its card changes together. */
    suspend fun saveNote(note: Note, createdCards: List<Card>, updatedCards: List<Card>)

    /** Deletes a note with its cards, memory and history. Only on explicit request. */
    suspend fun deleteNote(id: String)

    suspend fun cards(noteId: String): List<Card>
    suspend fun allCards(): List<Card>
    suspend fun card(id: CardId): Card?
    suspend fun cardCount(deckId: String): Int
    suspend fun maxNewPosition(): Long
}

/** Memory state, review history, workload bookkeeping, holidays and settings. */
interface ProgressRepository {
    /** Memory state of a card, or null if it has none yet (treated as new). */
    suspend fun memory(cardId: CardId): MemoryState?

    /**
     * Active (not suspended) cards that have been reviewed at least once and whose FSRS due
     * time is before [before], with their deferral bookkeeping.
     */
    suspend fun dueCards(before: Instant): List<DueCard>

    /** Active cards never reviewed, in introduction order, from one deck or (null) all of them. */
    suspend fun newCards(limit: Int, deckId: String? = null): List<Card>

    suspend fun countDue(from: Instant, until: Instant): Int

    /**
     * Records one real review: appends [record] to the history, stores its resulting memory
     * state and clears the card's deferral bookkeeping, all in one transaction.
     */
    suspend fun recordReview(record: ReviewRecord)

    suspend fun reviewsSince(since: Instant): List<ReviewRecord>
    suspend fun reviewsFor(cardId: CardId): List<ReviewRecord>
    suspend fun lastReviewTime(): Instant?
    suspend fun reviewCount(): Int

    suspend fun allMemory(): Map<CardId, MemoryState>

    suspend fun deferrals(): Map<CardId, DeferralState>
    suspend fun saveDeferrals(states: Map<CardId, DeferralState>)

    suspend fun recovery(): RecoveryState?
    suspend fun saveRecovery(state: RecoveryState?)

    suspend fun holidays(): List<HolidayPeriod>
    suspend fun saveHolidays(periods: List<HolidayPeriod>)

    suspend fun settings(): StudySettings
    suspend fun saveSettings(settings: StudySettings)
}

data class DueCard(val card: Card, val memory: MemoryState, val deferral: DeferralState?)
