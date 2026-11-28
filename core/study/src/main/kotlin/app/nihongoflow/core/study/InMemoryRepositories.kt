package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.workload.DeferralState
import app.nihongoflow.core.workload.RecoveryState
import kotlinx.datetime.Instant

/**
 * Reference implementation of both repositories, kept in memory. Used by tests and as the
 * behavioural specification the Room implementation must match.
 */
class InMemoryStore : CollectionRepository, ProgressRepository, ImportRepository {
    private val decks = linkedMapOf<String, Deck>()
    private val notes = linkedMapOf<String, Note>()
    private val cards = linkedMapOf<CardId, Card>()
    private val memory = mutableMapOf<CardId, MemoryState>()
    private val history = mutableListOf<ReviewRecord>()
    private var deferrals = mapOf<CardId, DeferralState>()
    private var recovery: RecoveryState? = null
    private var holidays = listOf<HolidayPeriod>()
    private var settings = StudySettings()

    override suspend fun decks() = decks.values.sortedBy { it.name.lowercase() }
    override suspend fun deck(id: String) = decks[id]
    override suspend fun saveDeck(deck: Deck) { decks[deck.id] = deck }

    override suspend fun deleteDeck(id: String) {
        notes.values.filter { it.deckId == id }.map { it.id }.forEach { deleteNote(it) }
        decks.remove(id)
    }

    override suspend fun notes(deckId: String) = notes.values.filter { it.deckId == deckId }
    override suspend fun note(id: String) = notes[id]
    override suspend fun allNotes() = notes.values.toList()

    override suspend fun saveNote(note: Note, createdCards: List<Card>, updatedCards: List<Card>) {
        notes[note.id] = note
        (createdCards + updatedCards).forEach { cards[it.id] = it }
    }

    override suspend fun importBatch(batch: ImportBatch) {
        batch.decks.forEach { decks[it.id] = it }
        batch.notes.forEach { notes[it.id] = it }
        (batch.createdCards + batch.updatedCards).forEach { cards[it.id] = it }
        memory.putAll(batch.memory)
        history += batch.history
    }

    override suspend fun deleteNote(id: String) {
        val ids = cards.values.filter { it.noteId == id }.map { it.id }.toSet()
        ids.forEach { cards.remove(it); memory.remove(it) }
        history.removeAll { it.cardId in ids }
        deferrals = deferrals - ids
        notes.remove(id)
    }

    override suspend fun cards(noteId: String) = cards.values.filter { it.noteId == noteId }
    override suspend fun allCards() = cards.values.toList()
    override suspend fun card(id: CardId) = cards[id]
    override suspend fun cardCount(deckId: String) = cards.values.count { it.deckId == deckId && !it.suspended }
    override suspend fun maxNewPosition() = cards.values.maxOfOrNull { it.newPosition } ?: 0L

    override suspend fun memory(cardId: CardId) = memory[cardId]

    override suspend fun dueCards(before: Instant) = cards.values
        .filter { !it.suspended }
        .mapNotNull { c -> memory[c.id]?.takeIf { !it.isNew && it.due < before }?.let { DueCard(c, it, deferrals[c.id]) } }

    override suspend fun newCards(limit: Int, deckId: String?) = cards.values
        .filter { !it.suspended && (deckId == null || it.deckId == deckId) && (memory[it.id]?.isNew ?: true) }
        .sortedBy { it.newPosition }
        .take(limit)

    override suspend fun countDue(from: Instant, until: Instant) = cards.values.count { c ->
        !c.suspended && memory[c.id]?.let { !it.isNew && it.due >= from && it.due < until } == true
    }

    override suspend fun recordReview(record: ReviewRecord) {
        history += record
        memory[record.cardId] = record.stateAfter
        deferrals = deferrals - record.cardId
    }

    override suspend fun reviewsSince(since: Instant) = history.filter { it.reviewedAt >= since }
    override suspend fun reviewsFor(cardId: CardId) = history.filter { it.cardId == cardId }
    override suspend fun lastReviewTime() = history.maxOfOrNull { it.reviewedAt }
    override suspend fun reviewCount() = history.size
    override suspend fun allMemory() = memory.toMap()

    override suspend fun deferrals() = deferrals
    override suspend fun saveDeferrals(states: Map<CardId, DeferralState>) { deferrals = states }
    override suspend fun recovery() = recovery
    override suspend fun saveRecovery(state: RecoveryState?) { recovery = state }
    override suspend fun holidays() = holidays
    override suspend fun saveHolidays(periods: List<HolidayPeriod>) { holidays = periods }
    override suspend fun settings() = settings
    override suspend fun saveSettings(settings: StudySettings) { this.settings = settings }
}
