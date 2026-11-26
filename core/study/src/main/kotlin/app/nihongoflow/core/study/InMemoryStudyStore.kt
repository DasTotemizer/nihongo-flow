package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.workload.DeferralState
import app.nihongoflow.core.workload.RecoveryState
import kotlinx.datetime.Instant

/**
 * A [StudyStore] kept in memory. Used by tests and UI previews; it follows the same
 * contract as the Room store, so the same tests describe both.
 */
class InMemoryStudyStore : StudyStore {
    private val decks = linkedMapOf<DeckId, Deck>()
    private val cards = linkedMapOf<CardId, Card>()
    private val memory = mutableMapOf<CardId, MemoryState>()
    private val history = mutableListOf<ReviewRecord>()
    private val deferrals = mutableMapOf<CardId, DeferralState>()
    private var recovery: RecoveryState? = null
    private var holidayList: List<HolidayPeriod> = emptyList()
    private var nextDeckId = 1L
    private var nextCardId = 1L
    private var nextPosition = 1L

    override suspend fun insertDeck(name: String, description: String, createdAt: Instant): DeckId {
        val id = nextDeckId++
        decks[id] = Deck(id, name, description, createdAt)
        return id
    }

    override suspend fun renameDeck(id: DeckId, name: String, description: String) {
        decks[id]?.let { decks[id] = it.copy(name = name, description = description) }
    }

    override suspend fun deleteDeck(id: DeckId) {
        decks.remove(id)
        cards.values.filter { it.deckId == id }.map { it.id }.forEach { removeCard(it) }
    }

    override suspend fun deck(id: DeckId): Deck? = decks[id]

    override suspend fun decks(): List<Deck> = decks.values.sortedBy { it.name.lowercase() }

    override suspend fun deckSummaries(dueBefore: Instant): List<DeckSummary> = decks().map { d ->
        val inDeck = cards.values.filter { it.deckId == d.id }
        DeckSummary(
            deck = d,
            cardCount = inDeck.size,
            newCount = inDeck.count { !it.suspended && memory.getValue(it.id).isNew },
            dueCount = inDeck.count { c ->
                val m = memory.getValue(c.id)
                !c.suspended && !m.isNew && m.due < dueBefore
            },
        )
    }

    override suspend fun insertCard(deckId: DeckId, content: CardContent, createdAt: Instant): CardId {
        require(deckId in decks) { "No deck $deckId" }
        val id = nextCardId++
        cards[id] = Card(id, deckId, content, createdAt, nextPosition++)
        memory[id] = MemoryState.new(createdAt)
        return id
    }

    override suspend fun updateCardContent(id: CardId, content: CardContent) {
        cards[id]?.let { cards[id] = it.copy(content = content) }
    }

    override suspend fun setSuspended(id: CardId, suspended: Boolean) {
        cards[id]?.let { cards[id] = it.copy(suspended = suspended) }
    }

    override suspend fun deleteCard(id: CardId) = removeCard(id)

    private fun removeCard(id: CardId) {
        cards.remove(id)
        memory.remove(id)
        deferrals.remove(id)
        history.removeAll { it.cardId == id }
    }

    override suspend fun card(id: CardId): Card? = cards[id]

    override suspend fun cards(deckId: DeckId): List<Card> =
        cards.values.filter { it.deckId == deckId }.sortedBy { it.newPosition }

    override suspend fun memoryState(id: CardId): MemoryState? = memory[id]

    private fun active() = cards.values.filter { !it.suspended }

    override suspend fun dueCards(before: Instant): List<Pair<CardId, MemoryState>> =
        active().map { it.id to memory.getValue(it.id) }
            .filter { (_, m) -> !m.isNew && m.due < before }
            .sortedWith(compareBy({ it.second.due }, { it.first }))

    override suspend fun newCardIds(limit: Int): List<CardId> =
        active().filter { memory.getValue(it.id).isNew }.sortedBy { it.newPosition }.take(limit).map { it.id }

    override suspend fun dueTimesBetween(from: Instant, to: Instant): List<Instant> =
        active().map { memory.getValue(it.id) }.filter { !it.isNew && it.due >= from && it.due < to }.map { it.due }

    override suspend fun allMemoryStates(): List<MemoryState> = active().map { memory.getValue(it.id) }

    override suspend fun applyReview(record: ReviewRecord) {
        require(record.cardId in cards) { "No card ${record.cardId}" }
        history += record
        memory[record.cardId] = record.stateAfter
        deferrals.remove(record.cardId)
    }

    override suspend fun reviewsSince(from: Instant): List<ReviewRecord> =
        history.filter { it.reviewedAt >= from }.sortedBy { it.reviewedAt }

    override suspend fun lastReviewTime(): Instant? = history.maxOfOrNull { it.reviewedAt }

    override suspend fun recentDurations(limit: Int): List<Long> =
        history.sortedByDescending { it.reviewedAt }.mapNotNull { it.durationMillis }.take(limit)

    override suspend fun deferralStates(cardIds: Collection<CardId>): Map<CardId, DeferralState> =
        cardIds.mapNotNull { id -> deferrals[id]?.let { id to it } }.toMap()

    override suspend fun saveDeferralStates(states: Map<CardId, DeferralState>) {
        deferrals.putAll(states.filterKeys { it in cards })
    }

    override suspend fun recoveryState(): RecoveryState? = recovery

    override suspend fun saveRecoveryState(state: RecoveryState?) {
        recovery = state
    }

    override suspend fun holidays(): List<HolidayPeriod> = holidayList

    override suspend fun saveHolidays(periods: List<HolidayPeriod>) {
        holidayList = periods.sortedBy { it.startsOn }
    }
}
