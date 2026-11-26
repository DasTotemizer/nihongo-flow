package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.holiday.HolidayRules
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.model.StudyDayClock
import app.nihongoflow.core.scheduler.FsrsScheduler
import app.nihongoflow.core.workload.DeferralLedger
import app.nihongoflow.core.workload.NewCardCandidate
import app.nihongoflow.core.workload.PaceEstimate
import app.nihongoflow.core.workload.ReviewCandidate
import app.nihongoflow.core.workload.SessionPlan
import app.nihongoflow.core.workload.SessionSize
import app.nihongoflow.core.workload.TodayProgress
import app.nihongoflow.core.workload.WorkloadManager
import app.nihongoflow.core.workload.WorkloadRequest
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import java.util.UUID
import kotlin.time.Duration.Companion.days

/** What the home screen needs. */
data class HomeState(
    val plan: SessionPlan,
    val holiday: HolidayStatus,
    val deckCount: Int,
    val cardCount: Int,
)

sealed interface SaveNoteResult {
    data class Saved(val note: Note, val cardsCreated: Int) : SaveNoteResult

    /** Nothing saved: these existing notes look like the same item. Call again with allowDuplicate. */
    data class PossibleDuplicate(val existing: List<Note>) : SaveNoteResult

    data class Invalid(val reason: String) : SaveNoteResult
}

/**
 * The single entry point the UI uses for studying. It joins the memory scheduler, the
 * workload manager and Holiday Mode over the repositories, and keeps every rule in one
 * place so the UI holds no learning logic.
 */
class StudyService(
    private val collection: CollectionRepository,
    private val progress: ProgressRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private suspend fun dayClock() = dayClock(progress.settings())

    private fun dayClock(settings: StudySettings) = StudyDayClock(timeZone(), settings.dayStartHour)

    private fun scheduler(settings: StudySettings) = FsrsScheduler(settings.scheduler())

    // ---- Planning and reviewing -------------------------------------------------------

    suspend fun home(size: SessionSize = SessionSize.SUGGESTED): HomeState {
        val decks = collection.decks()
        return HomeState(
            plan = plan(size),
            holiday = holidayStatus(),
            deckCount = decks.size,
            cardCount = decks.sumOf { collection.cardCount(it.id) },
        )
    }

    suspend fun plan(size: SessionSize = SessionSize.SUGGESTED): SessionPlan {
        val settings = progress.settings()
        val now = clock.now()
        val day = dayClock(settings)
        val today = day.studyDayOf(now)
        val endOfToday = day.endOf(today)
        val templates = ReviewDirection.enabledTemplates(settings.enabledDirections)

        val due = progress.dueCards(before = endOfToday)
            .filter { it.card.template in templates }
            .map { ReviewCandidate(it.card.id, it.memory, it.deferral) }
        val newCards = progress.newCards(limit = settings.newCardsPerDay * 4 + 20)
            .filter { it.template in templates }
            .map { NewCardCandidate(it.id) }

        val todays = progress.reviewsSince(day.startOf(today))
        val lastReview = progress.lastReviewTime()
        val holidays = progress.holidays()
        val lastStudyDay = lastReview?.let { day.studyDayOf(it) }
        val forecast = progress.countDue(endOfToday, day.endOf(today.plus(7, DateTimeUnit.DAY))) / 7.0
        val pace = PaceEstimate.fromRecentReviews(
            progress.reviewsSince(now - 30.days).mapNotNull { it.durationMillis },
        )

        val manager = WorkloadManager(settings.workload(), scheduler(settings))
        val plan = manager.plan(
            WorkloadRequest(
                now = now,
                clock = day,
                dueCards = due,
                newCards = newCards,
                today = TodayProgress(
                    reviewsDone = todays.count { !it.stateBefore.isNew },
                    newCardsIntroduced = todays.filter { it.stateBefore.isNew }.map { it.cardId }.distinct().size,
                ),
                lastStudyDay = lastStudyDay,
                recovery = progress.recovery(),
                holiday = HolidayRules.statusOn(today, holidays),
                pace = pace,
                size = size,
                forecastDuePerDay = forecast,
                cameBackFromHoliday = HolidayRules.justEnded(holidays, today, lastStudyDay) != null,
            ),
        )
        progress.saveRecovery(plan.nextRecoveryState)
        return plan
    }

    /** Starts a session from [plan]: records fair-deferral bookkeeping and returns the queue. */
    suspend fun startSession(plan: SessionPlan): ReviewSession {
        progress.saveDeferrals(DeferralLedger.recordSessionStart(progress.deferrals(), plan))
        return ReviewSession(plan.queue)
    }

    suspend fun cardFace(cardId: CardId): CardFace? {
        val card = collection.card(cardId) ?: return null
        val note = collection.note(card.noteId) ?: return null
        return CardRenderer.face(note, card)
    }

    /** Due time for each answer, for the answer buttons. */
    suspend fun preview(cardId: CardId): Map<Rating, Instant> {
        val settings = progress.settings()
        val now = clock.now()
        val state = memoryOf(cardId, now)
        return scheduler(settings).preview(state, now)
    }

    /** Applies one real answer and records it. Returns the new memory state. */
    suspend fun answer(cardId: CardId, rating: Rating, durationMillis: Long?): MemoryState {
        val settings = progress.settings()
        val now = clock.now()
        val before = memoryOf(cardId, now)
        val after = scheduler(settings).review(before, rating, now)
        progress.recordReview(ReviewRecord(cardId, rating, now, durationMillis, before, after))
        return after
    }

    private suspend fun memoryOf(cardId: CardId, now: Instant): MemoryState =
        progress.memory(cardId) ?: MemoryState.new(collection.card(cardId)?.createdAt ?: now)

    // ---- Decks and notes ----------------------------------------------------------------

    suspend fun decks(): List<Deck> = collection.decks()
    suspend fun deck(id: String): Deck? = collection.deck(id)
    suspend fun notes(deckId: String): List<Note> = collection.notes(deckId)
    suspend fun note(id: String): Note? = collection.note(id)
    suspend fun cardCount(deckId: String): Int = collection.cardCount(deckId)

    suspend fun createDeck(name: String, description: String = "", source: ContentSource = ContentSource.USER): Deck {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A deck needs a name" }
        val deck = Deck(newId(), clean, description.trim(), source = source, createdAt = clock.now())
        collection.saveDeck(deck)
        return deck
    }

    suspend fun renameDeck(id: String, name: String, description: String) {
        val deck = collection.deck(id) ?: return
        require(name.isNotBlank()) { "A deck needs a name" }
        collection.saveDeck(deck.copy(name = name.trim(), description = description.trim()))
    }

    suspend fun deleteDeck(id: String) = collection.deleteDeck(id)

    /**
     * Creates or updates a note. Editing keeps every existing card and its history; cards
     * an edit no longer needs are suspended, not deleted.
     */
    suspend fun saveNote(
        deckId: String,
        kind: NoteKind,
        fields: Map<String, String>,
        tags: Set<String> = emptySet(),
        existingId: String? = null,
        allowDuplicate: Boolean = false,
        source: ContentSource = ContentSource.USER,
    ): SaveNoteResult {
        val clean = fields.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        validate(kind, clean)?.let { return SaveNoteResult.Invalid(it) }
        val now = clock.now()
        val previous = existingId?.let { collection.note(it) }
        if (!allowDuplicate) {
            val dupes = findDuplicates(kind, clean).filter { it.id != existingId }
            if (dupes.isNotEmpty()) return SaveNoteResult.PossibleDuplicate(dupes)
        }
        val note = Note(
            id = previous?.id ?: newId(),
            deckId = deckId,
            kind = kind,
            fields = (previous?.fields.orEmpty().filterKeys { it !in knownFields } + clean),
            tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            source = previous?.source ?: source,
            createdAt = previous?.createdAt ?: now,
            updatedAt = now,
        )
        var position = collection.maxNewPosition()
        val existingCards = previous?.let { collection.cards(it.id) }.orEmpty()
        val r = CardGenerator.reconcile(note, existingCards, now, nextNewPosition = { ++position }, newId = newId)
        collection.saveNote(note, r.created, r.updated)
        return SaveNoteResult.Saved(note, r.created.size)
    }

    suspend fun deleteNote(id: String) = collection.deleteNote(id)

    /** Notes of the same kind that are the same item: same written form and reading, or same text. */
    suspend fun findDuplicates(kind: NoteKind, fields: Map<String, String>): List<Note> {
        val key = duplicateKey(kind, fields) ?: return emptyList()
        return collection.allNotes().filter { it.kind == kind && duplicateKey(it.kind, it.fields) == key }
    }

    private fun duplicateKey(kind: NoteKind, f: Map<String, String>): String? = NoteRules.duplicateKey(kind, f)

    private fun validate(kind: NoteKind, f: Map<String, String>): String? = NoteRules.problem(kind, f)

    private val knownFields = setOf(
        Fields.WORD, Fields.READING, Fields.MEANING, Fields.SENTENCE, Fields.SENTENCE_READING, Fields.SENTENCE_MEANING,
        Fields.PITCH, Fields.JLPT, Fields.KANJI, Fields.ONYOMI, Fields.KUNYOMI, Fields.PATTERN, Fields.EXPLANATION,
        Fields.EXAMPLE, Fields.EXAMPLE_MEANING, Fields.FRONT, Fields.BACK, Fields.TEXT, Fields.EXTRA, Fields.NOTES,
    )

    // ---- Holiday Mode -------------------------------------------------------------------

    suspend fun holidayStatus(): HolidayStatus {
        val today = dayClock().studyDayOf(clock.now())
        return HolidayRules.statusOn(today, progress.holidays())
    }

    suspend fun today(): LocalDate = dayClock().studyDayOf(clock.now())

    /** Starts or schedules a holiday. Fails if it overlaps another one. */
    suspend fun scheduleHoliday(mode: HolidayMode, startsOn: LocalDate, endsOn: LocalDate?): Result<HolidayPeriod> {
        val period = runCatching {
            HolidayPeriod(newId(), mode, startsOn, if (mode == HolidayMode.INDEFINITE_PAUSE) null else endsOn)
        }.getOrElse { return Result.failure(it) }
        return HolidayRules.schedule(progress.holidays(), period).map {
            progress.saveHolidays(it)
            period
        }
    }

    /** Changes the mode or end date of the holiday in progress. */
    suspend fun changeActiveHoliday(mode: HolidayMode, endsOn: LocalDate?): Result<HolidayPeriod> {
        val active = holidayStatus() as? HolidayStatus.Active
            ?: return Result.failure(IllegalStateException("No holiday is active"))
        val changed = runCatching {
            active.period.copy(mode = mode, endsOn = if (mode == HolidayMode.INDEFINITE_PAUSE) null else endsOn)
        }.getOrElse { return Result.failure(it) }
        return HolidayRules.schedule(progress.holidays(), changed).map {
            progress.saveHolidays(it)
            changed
        }
    }

    suspend fun endHolidayNow() {
        progress.saveHolidays(HolidayRules.endNow(progress.holidays(), today()))
    }

    suspend fun cancelHoliday(id: String) {
        progress.saveHolidays(HolidayRules.cancelUpcoming(progress.holidays(), id, today()))
    }

    // ---- Settings and statistics --------------------------------------------------------

    suspend fun settings(): StudySettings = progress.settings()

    suspend fun saveSettings(settings: StudySettings) {
        settings.workload() // validates
        settings.scheduler()
        progress.saveSettings(settings)
    }

    suspend fun stats(): ProgressStats {
        val settings = progress.settings()
        val now = clock.now()
        return Statistics.compute(
            now = now,
            notes = collection.allNotes(),
            memory = progress.allMemory(),
            reviews = progress.reviewsSince(Instant.DISTANT_PAST),
            cardsByNote = { collection.cards(it) },
            scheduler = scheduler(settings),
            dayClock = dayClock(settings),
            today = plan(),
        )
    }
}
