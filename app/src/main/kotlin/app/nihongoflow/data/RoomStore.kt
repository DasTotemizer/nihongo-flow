package app.nihongoflow.data

import androidx.room.withTransaction
import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.model.ReviewSource
import app.nihongoflow.core.study.Card
import app.nihongoflow.core.study.CardTemplate
import app.nihongoflow.core.study.CollectionRepository
import app.nihongoflow.core.study.ContentSource
import app.nihongoflow.core.study.Deck
import app.nihongoflow.core.study.DueCard
import app.nihongoflow.core.study.Note
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.ProgressRepository
import app.nihongoflow.core.study.ReviewDirection
import app.nihongoflow.core.study.SourceKind
import app.nihongoflow.core.study.StudySettings
import app.nihongoflow.core.workload.DeferralState
import app.nihongoflow.core.workload.RecoveryPacing
import app.nihongoflow.core.workload.RecoveryState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import org.json.JSONArray
import org.json.JSONObject

/** Room-backed implementation of both repositories. Behaviour matches InMemoryStore. */
class RoomStore(private val db: NihongoFlowDatabase) : CollectionRepository, ProgressRepository {

    private val c = db.collectionDao()
    private val p = db.progressDao()

    // ---- Collection ----

    override suspend fun decks() = c.decks().map { it.toDomain() }
    override suspend fun deck(id: String) = c.deck(id)?.toDomain()
    override suspend fun saveDeck(deck: Deck) = c.upsertDeck(deck.toEntity())

    override suspend fun deleteDeck(id: String) = db.withTransaction {
        c.noteIds(id).forEach { deleteNoteInternal(it) }
        c.deleteDeckRow(id)
    }

    override suspend fun notes(deckId: String) = c.notes(deckId).map { it.toDomain() }
    override suspend fun note(id: String) = c.note(id)?.toDomain()
    override suspend fun allNotes() = c.allNotes().map { it.toDomain() }

    override suspend fun saveNote(note: Note, createdCards: List<Card>, updatedCards: List<Card>) = db.withTransaction {
        c.upsertNote(note.toEntity())
        val all = createdCards + updatedCards
        if (all.isNotEmpty()) c.upsertCards(all.map { it.toEntity() })
    }

    override suspend fun deleteNote(id: String) = db.withTransaction { deleteNoteInternal(id) }

    private suspend fun deleteNoteInternal(id: String) {
        val ids = c.cardIds(id)
        ids.chunked(CHUNK).forEach {
            p.deleteMemory(it)
            p.deleteReviews(it)
            p.deleteDeferrals(it)
        }
        c.deleteCardsOfNote(id)
        c.deleteNoteRow(id)
    }

    override suspend fun cards(noteId: String) = c.cards(noteId).map { it.toDomain() }
    override suspend fun card(id: CardId) = c.card(id)?.toDomain()
    override suspend fun cardCount(deckId: String) = c.cardCount(deckId)
    override suspend fun maxNewPosition() = c.maxNewPosition()

    // ---- Progress ----

    override suspend fun memory(cardId: CardId) = p.memory(cardId)?.toDomain()

    override suspend fun dueCards(before: Instant): List<DueCard> {
        val memory = p.dueMemory(before.toEpochMilliseconds())
        if (memory.isEmpty()) return emptyList()
        val ids = memory.map { it.cardId }
        val cards = ids.chunked(CHUNK).flatMap { c.cardsByIds(it) }.associateBy { it.id }
        val deferrals = ids.chunked(CHUNK).flatMap { p.deferralsFor(it) }.associateBy { it.cardId }
        return memory.mapNotNull { m ->
            cards[m.cardId]?.let { DueCard(it.toDomain(), m.toDomain(), deferrals[m.cardId]?.toDomain()) }
        }
    }

    override suspend fun newCards(limit: Int) = c.newCards(limit).map { it.toDomain() }

    override suspend fun countDue(from: Instant, until: Instant) =
        p.countDue(from.toEpochMilliseconds(), until.toEpochMilliseconds())

    override suspend fun recordReview(record: ReviewRecord) = db.withTransaction {
        p.insertReview(record.toEntity())
        p.upsertMemory(record.stateAfter.toEntity(record.cardId))
        p.deleteDeferrals(listOf(record.cardId))
    }

    override suspend fun reviewsSince(since: Instant) =
        p.reviewsSince(since.toEpochMillisClamped()).map { it.toDomain() }

    override suspend fun reviewsFor(cardId: CardId) = p.reviewsFor(cardId).map { it.toDomain() }
    override suspend fun lastReviewTime() = p.lastReviewTime()?.let { Instant.fromEpochMilliseconds(it) }
    override suspend fun reviewCount() = p.reviewCount()
    override suspend fun allMemory() = p.allMemory().associate { it.cardId to it.toDomain() }

    override suspend fun deferrals() = p.deferrals().associate { it.cardId to it.toDomain() }

    override suspend fun saveDeferrals(states: Map<CardId, DeferralState>) = db.withTransaction {
        p.clearDeferrals()
        if (states.isNotEmpty()) p.upsertDeferrals(states.map { (id, s) -> s.toEntity(id) })
    }

    override suspend fun recovery() = p.recovery()?.toDomain()

    override suspend fun saveRecovery(state: RecoveryState?) {
        if (state == null) p.clearRecovery() else p.upsertRecovery(state.toEntity())
    }

    override suspend fun holidays() = p.holidays().map { it.toDomain() }

    override suspend fun saveHolidays(periods: List<HolidayPeriod>) = db.withTransaction {
        p.clearHolidays()
        if (periods.isNotEmpty()) p.upsertHolidays(periods.map { it.toEntity() })
    }

    override suspend fun settings() = p.settings()?.toDomain() ?: StudySettings()
    override suspend fun saveSettings(settings: StudySettings) = p.upsertSettings(settings.toEntity())

    private companion object {
        /** SQLite limits the number of bound parameters; stay well under it. */
        const val CHUNK = 500

        fun Instant.toEpochMillisClamped(): Long =
            if (this <= Instant.fromEpochMilliseconds(0)) 0 else toEpochMilliseconds()

        fun ms(v: Long) = Instant.fromEpochMilliseconds(v)

        fun ContentSource.kindName() = kind.name

        fun DeckEntity.toDomain() = Deck(
            id, name, description, parentId,
            ContentSource(SourceKind.valueOf(sourceKind), sourceName, sourceAuthor, sourceLicence, sourceUrl),
            ms(createdAt),
        )

        fun Deck.toEntity() = DeckEntity(
            id, name, description, parentId, source.kindName(), source.name, source.author, source.licence, source.url,
            createdAt.toEpochMilliseconds(),
        )

        fun NoteEntity.toDomain(): Note {
            val obj = JSONObject(fieldsJson)
            val fields = obj.keys().asSequence().associateWith { obj.getString(it) }
            val arr = JSONArray(tagsJson)
            val tags = (0 until arr.length()).map { arr.getString(it) }.toSet()
            return Note(
                id, deckId, NoteKind.valueOf(kind), fields, tags,
                ContentSource(SourceKind.valueOf(sourceKind), sourceName, sourceAuthor, sourceLicence, sourceUrl),
                ms(createdAt), ms(updatedAt),
            )
        }

        fun Note.toEntity() = NoteEntity(
            id, deckId, kind.name,
            JSONObject().apply { fields.forEach { (k, v) -> put(k, v) } }.toString(),
            JSONArray().apply { tags.sorted().forEach { put(it) } }.toString(),
            source.kindName(), source.name, source.author, source.licence, source.url,
            createdAt.toEpochMilliseconds(), updatedAt.toEpochMilliseconds(),
        )

        fun CardEntity.toDomain() = Card(
            id, noteId, deckId, CardTemplate.valueOf(template), clozeNumber, ms(createdAt), newPosition, suspended,
        )

        fun Card.toEntity() = CardEntity(
            id, noteId, deckId, template.name, clozeNumber, createdAt.toEpochMilliseconds(), newPosition, suspended,
        )

        fun MemoryStateEntity.toDomain() = MemoryState(
            MemoryPhase.valueOf(phase), step, stability, difficulty, ms(due), lastReview?.let(::ms),
        )

        fun MemoryState.toEntity(cardId: String) = MemoryStateEntity(
            cardId, phase.name, step, stability, difficulty, due.toEpochMilliseconds(), lastReview?.toEpochMilliseconds(),
        )

        fun ReviewEntity.toDomain() = ReviewRecord(
            cardId = cardId,
            rating = Rating.valueOf(rating),
            reviewedAt = ms(reviewedAt),
            durationMillis = durationMillis,
            stateBefore = MemoryState(
                MemoryPhase.valueOf(beforePhase), beforeStep, beforeStability, beforeDifficulty,
                ms(beforeDue), beforeLastReview?.let(::ms),
            ),
            stateAfter = MemoryState(
                MemoryPhase.valueOf(afterPhase), afterStep, afterStability, afterDifficulty,
                ms(afterDue), afterLastReview?.let(::ms),
            ),
            source = ReviewSource.valueOf(source),
        )

        fun ReviewRecord.toEntity() = ReviewEntity(
            cardId = cardId,
            rating = rating.name,
            reviewedAt = reviewedAt.toEpochMilliseconds(),
            durationMillis = durationMillis,
            source = source.name,
            beforePhase = stateBefore.phase.name,
            beforeStep = stateBefore.step,
            beforeStability = stateBefore.stability,
            beforeDifficulty = stateBefore.difficulty,
            beforeDue = stateBefore.due.toEpochMilliseconds(),
            beforeLastReview = stateBefore.lastReview?.toEpochMilliseconds(),
            afterPhase = stateAfter.phase.name,
            afterStep = stateAfter.step,
            afterStability = stateAfter.stability,
            afterDifficulty = stateAfter.difficulty,
            afterDue = stateAfter.due.toEpochMilliseconds(),
            afterLastReview = stateAfter.lastReview?.toEpochMilliseconds(),
        )

        fun DeferralEntity.toDomain() =
            DeferralState(LocalDate.parse(firstDeferredOn), LocalDate.parse(lastDeferredOn), studyDaysDeferred)

        fun DeferralState.toEntity(cardId: String) =
            DeferralEntity(cardId, firstDeferredOn.toString(), lastDeferredOn.toString(), studyDaysDeferred)

        fun RecoveryEntity.toDomain() = RecoveryState(
            LocalDate.parse(startedOn), backlogAtStart, easeIn, studyDaysCompleted, lastCountedStudyDay?.let(LocalDate::parse),
        )

        fun RecoveryState.toEntity() = RecoveryEntity(
            startedOn = startedOn.toString(),
            backlogAtStart = backlogAtStart,
            easeIn = easeIn,
            studyDaysCompleted = studyDaysCompleted,
            lastCountedStudyDay = lastCountedStudyDay?.toString(),
        )

        fun HolidayEntity.toDomain() =
            HolidayPeriod(id, HolidayMode.valueOf(mode), LocalDate.parse(startsOn), endsOn?.let(LocalDate::parse))

        fun HolidayPeriod.toEntity() = HolidayEntity(id, mode.name, startsOn.toString(), endsOn?.toString())

        fun SettingsEntity.toDomain() = StudySettings(
            desiredRetention = desiredRetention,
            targetSessionMinutes = targetSessionMinutes,
            maxSessionMinutes = maxSessionMinutes,
            maxReviewsPerSession = maxReviewsPerSession,
            newCardsPerDay = newCardsPerDay,
            recoveryPacing = RecoveryPacing.valueOf(recoveryPacing),
            preferredStudyDays = preferredStudyDays.split(",").mapNotNull { it.trim().toIntOrNull() }
                .map { DayOfWeek(it) }.toSet(),
            casualSessionMinutes = casualSessionMinutes,
            dayStartHour = dayStartHour,
            enabledDirections = enabledDirections.split(",").filter { it.isNotBlank() }
                .mapNotNull { name -> ReviewDirection.entries.firstOrNull { it.name == name.trim() } }.toSet(),
            twoButtonReview = twoButtonReview,
        )

        fun StudySettings.toEntity() = SettingsEntity(
            desiredRetention = desiredRetention,
            targetSessionMinutes = targetSessionMinutes,
            maxSessionMinutes = maxSessionMinutes,
            maxReviewsPerSession = maxReviewsPerSession,
            newCardsPerDay = newCardsPerDay,
            recoveryPacing = recoveryPacing.name,
            preferredStudyDays = preferredStudyDays.map { it.isoDayNumber }.sorted().joinToString(","),
            casualSessionMinutes = casualSessionMinutes,
            dayStartHour = dayStartHour,
            enabledDirections = enabledDirections.map { it.name }.sorted().joinToString(","),
            twoButtonReview = twoButtonReview,
        )
    }
}
