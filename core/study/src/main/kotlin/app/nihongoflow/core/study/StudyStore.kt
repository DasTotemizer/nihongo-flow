package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.workload.DeferralState
import app.nihongoflow.core.workload.RecoveryState
import kotlinx.datetime.Instant

/**
 * Storage port for everything the study logic keeps. The Android app implements it with
 * Room (`:data`); tests and previews use [InMemoryStudyStore].
 *
 * The three kinds of state are kept apart, as the architecture requires:
 * - memory state (owned by the scheduler) changes only in [applyReview];
 * - review history is append-only;
 * - presentation bookkeeping (deferrals, recovery, holidays) never touches memory state.
 */
interface StudyStore {

    // Decks

    suspend fun insertDeck(name: String, description: String, createdAt: Instant): DeckId
    suspend fun renameDeck(id: DeckId, name: String, description: String)

    /** Deletes the deck with its cards, their memory state, history and bookkeeping. */
    suspend fun deleteDeck(id: DeckId)
    suspend fun deck(id: DeckId): Deck?
    suspend fun decks(): List<Deck>

    /** Per-deck counts. Due means FSRS due before [dueBefore] and not suspended. */
    suspend fun deckSummaries(dueBefore: Instant): List<DeckSummary>

    // Cards

    /** Adds a card at the end of the new-card order, with a fresh memory state. */
    suspend fun insertCard(deckId: DeckId, content: CardContent, createdAt: Instant): CardId
    suspend fun updateCardContent(id: CardId, content: CardContent)
    suspend fun setSuspended(id: CardId, suspended: Boolean)
    suspend fun deleteCard(id: CardId)
    suspend fun card(id: CardId): Card?
    suspend fun cards(deckId: DeckId): List<Card>
    suspend fun memoryState(id: CardId): MemoryState?

    /** Cards that have been reviewed at least once, are not suspended and are due before [before]. */
    suspend fun dueCards(before: Instant): List<Pair<CardId, MemoryState>>

    /** Never-reviewed, unsuspended cards in introduction order. */
    suspend fun newCardIds(limit: Int): List<CardId>

    /** Due times of reviewed, unsuspended cards due in [from, to). Used for the forecast. */
    suspend fun dueTimesBetween(from: Instant, to: Instant): List<Instant>

    /** Memory states of every unsuspended card, for statistics. */
    suspend fun allMemoryStates(): List<MemoryState>

    // Reviews

    /**
     * Records one real answer atomically: appends [record] to the history, stores
     * `record.stateAfter` as the card's memory state and clears its deferral bookkeeping.
     */
    suspend fun applyReview(record: ReviewRecord)

    /** Reviews at or after [from], oldest first. */
    suspend fun reviewsSince(from: Instant): List<ReviewRecord>
    suspend fun lastReviewTime(): Instant?

    /** Answer times of the most recent reviews, newest first. */
    suspend fun recentDurations(limit: Int): List<Long>

    // Presentation bookkeeping

    suspend fun deferralStates(cardIds: Collection<CardId>): Map<CardId, DeferralState>
    /** Inserts or replaces the given states. Other cards keep theirs. */
    suspend fun saveDeferralStates(states: Map<CardId, DeferralState>)
    suspend fun recoveryState(): RecoveryState?
    suspend fun saveRecoveryState(state: RecoveryState?)
    suspend fun holidays(): List<HolidayPeriod>
    suspend fun saveHolidays(periods: List<HolidayPeriod>)
}
