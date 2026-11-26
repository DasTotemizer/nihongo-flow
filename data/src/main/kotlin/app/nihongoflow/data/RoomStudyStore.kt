package app.nihongoflow.data

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.model.ReviewSource
import app.nihongoflow.core.study.Card
import app.nihongoflow.core.study.CardContent
import app.nihongoflow.core.study.Deck
import app.nihongoflow.core.study.DeckId
import app.nihongoflow.core.study.DeckSummary
import app.nihongoflow.core.study.StudyStore
import app.nihongoflow.core.workload.DeferralState
import app.nihongoflow.core.workload.RecoveryState
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** [StudyStore] on the Room database. Only converts types; the rules live in :core:study. */
class RoomStudyStore(database: NihongoFlowDatabase) : StudyStore {
    private val dao = database.studyDao()

    override suspend fun insertDeck(name: String, description: String, createdAt: Instant): DeckId =
        dao.insertDeck(DeckEntity(name = name, description = description, createdAt = createdAt.toEpochMilliseconds()))

    override suspend fun renameDeck(id: DeckId, name: String, description: String) = dao.renameDeck(id, name, description)

    override suspend fun deleteDeck(id: DeckId) = dao.deleteDeck(id)

    override suspend fun deck(id: DeckId): Deck? = dao.deck(id)?.toDeck()

    override suspend fun decks(): List<Deck> = dao.decks().map { it.toDeck() }

    override suspend fun deckSummaries(dueBefore: Instant): List<DeckSummary> =
        dao.deckSummaries(dueBefore.toEpochMilliseconds()).map {
            DeckSummary(it.deck.toDeck(), it.cardCount, it.newCount, it.dueCount)
        }

    override suspend fun insertCard(deckId: DeckId, content: CardContent, createdAt: Instant): CardId {
        require(dao.deck(deckId) != null) { "No deck $deckId" }
        return dao.insertCard(
            CardEntity(
                deckId = deckId,
                front = content.front,
                back = content.back,
                reading = content.reading,
                notes = content.notes,
                createdAt = createdAt.toEpochMilliseconds(),
                newPosition = 0, // assigned in the transaction
                suspended = false,
            ),
            MemoryState.new(createdAt).toColumns(),
        )
    }

    override suspend fun updateCardContent(id: CardId, content: CardContent) =
        dao.updateCardContent(id, content.front, content.back, content.reading, content.notes)

    override suspend fun setSuspended(id: CardId, suspended: Boolean) = dao.setSuspended(id, suspended)

    override suspend fun deleteCard(id: CardId) = dao.deleteCard(id)

    override suspend fun card(id: CardId): Card? = dao.card(id)?.toCard()

    override suspend fun cards(deckId: DeckId): List<Card> = dao.cards(deckId).map { it.toCard() }

    override suspend fun memoryState(id: CardId): MemoryState? = dao.memoryState(id)?.memory?.toState()

    override suspend fun dueCards(before: Instant): List<Pair<CardId, MemoryState>> =
        dao.dueCards(before.toEpochMilliseconds()).map { it.cardId to it.memory.toState() }

    override suspend fun newCardIds(limit: Int): List<CardId> = dao.newCardIds(limit)

    override suspend fun dueTimesBetween(from: Instant, to: Instant): List<Instant> =
        dao.dueTimesBetween(from.toEpochMilliseconds(), to.toEpochMilliseconds()).map(Instant::fromEpochMilliseconds)

    override suspend fun allMemoryStates(): List<MemoryState> = dao.activeMemoryStates().map { it.memory.toState() }

    override suspend fun applyReview(record: ReviewRecord) {
        require(dao.card(record.cardId) != null) { "No card ${record.cardId}" }
        dao.applyReview(
            ReviewLogEntity(
                cardId = record.cardId,
                rating = record.rating.name,
                reviewedAt = record.reviewedAt.toEpochMilliseconds(),
                durationMillis = record.durationMillis,
                source = record.source.name,
                before = record.stateBefore.toColumns(),
                after = record.stateAfter.toColumns(),
            ),
            MemoryStateEntity(record.cardId, record.stateAfter.toColumns()),
        )
    }

    override suspend fun reviewsSince(from: Instant): List<ReviewRecord> =
        dao.reviewsSince(from.toEpochMilliseconds()).map {
            ReviewRecord(
                cardId = it.cardId,
                rating = Rating.valueOf(it.rating),
                reviewedAt = Instant.fromEpochMilliseconds(it.reviewedAt),
                durationMillis = it.durationMillis,
                stateBefore = it.before.toState(),
                stateAfter = it.after.toState(),
                source = ReviewSource.valueOf(it.source),
            )
        }

    override suspend fun lastReviewTime(): Instant? = dao.lastReviewTime()?.let(Instant::fromEpochMilliseconds)

    override suspend fun recentDurations(limit: Int): List<Long> = dao.recentDurations(limit)

    override suspend fun deferralStates(cardIds: Collection<CardId>): Map<CardId, DeferralState> =
        cardIds.distinct().chunked(SQL_VARIABLE_CHUNK).flatMap { dao.deferrals(it) }.associate {
            it.cardId to DeferralState(LocalDate.parse(it.firstDeferredOn), LocalDate.parse(it.lastDeferredOn), it.studyDaysDeferred)
        }

    override suspend fun saveDeferralStates(states: Map<CardId, DeferralState>) {
        if (states.isEmpty()) return
        dao.upsertDeferrals(
            states.map { (id, s) ->
                DeferralEntity(id, s.firstDeferredOn.toString(), s.lastDeferredOn.toString(), s.studyDaysDeferred)
            },
        )
    }

    override suspend fun recoveryState(): RecoveryState? = dao.recovery()?.let {
        RecoveryState(
            startedOn = LocalDate.parse(it.startedOn),
            backlogAtStart = it.backlogAtStart,
            easeIn = it.easeIn,
            studyDaysCompleted = it.studyDaysCompleted,
            lastCountedStudyDay = it.lastCountedStudyDay?.let(LocalDate::parse),
        )
    }

    override suspend fun saveRecoveryState(state: RecoveryState?) {
        if (state == null) return dao.clearRecovery()
        dao.saveRecovery(
            RecoveryEntity(
                startedOn = state.startedOn.toString(),
                backlogAtStart = state.backlogAtStart,
                easeIn = state.easeIn,
                studyDaysCompleted = state.studyDaysCompleted,
                lastCountedStudyDay = state.lastCountedStudyDay?.toString(),
            ),
        )
    }

    override suspend fun holidays(): List<HolidayPeriod> = dao.holidays().map {
        HolidayPeriod(it.id, HolidayMode.valueOf(it.mode), LocalDate.parse(it.startsOn), it.endsOn?.let(LocalDate::parse))
    }

    override suspend fun saveHolidays(periods: List<HolidayPeriod>) =
        dao.replaceHolidays(periods.map { HolidayEntity(it.id, it.mode.name, it.startsOn.toString(), it.endsOn?.toString()) })

    private companion object {
        /** Below SQLite's default limit of 999 bound variables. */
        const val SQL_VARIABLE_CHUNK = 500

        fun DeckEntity.toDeck() = Deck(id, name, description, Instant.fromEpochMilliseconds(createdAt))

        fun CardEntity.toCard() = Card(
            id = id,
            deckId = deckId,
            content = CardContent(front, back, reading, notes),
            createdAt = Instant.fromEpochMilliseconds(createdAt),
            newPosition = newPosition,
            suspended = suspended,
        )

        fun MemoryState.toColumns() = MemoryColumns(
            phase = phase.name,
            step = step,
            stability = stability,
            difficulty = difficulty,
            due = due.toEpochMilliseconds(),
            lastReview = lastReview?.toEpochMilliseconds(),
        )

        fun MemoryColumns.toState() = MemoryState(
            phase = MemoryPhase.valueOf(phase),
            step = step,
            stability = stability,
            difficulty = difficulty,
            due = Instant.fromEpochMilliseconds(due),
            lastReview = lastReview?.let(Instant::fromEpochMilliseconds),
        )
    }
}
