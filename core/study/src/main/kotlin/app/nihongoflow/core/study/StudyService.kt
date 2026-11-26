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
import app.nihongoflow.core.scheduler.MemoryScheduler
import app.nihongoflow.core.workload.DeferralLedger
import app.nihongoflow.core.workload.NewCardCandidate
import app.nihongoflow.core.workload.PaceEstimate
import app.nihongoflow.core.workload.ReviewCandidate
import app.nihongoflow.core.workload.SessionPlan
import app.nihongoflow.core.workload.SessionSize
import app.nihongoflow.core.workload.TodayProgress
import app.nihongoflow.core.workload.WorkloadManager
import app.nihongoflow.core.workload.WorkloadRequest
import app.nihongoflow.core.workload.WorkloadSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

/** What the Home screen needs: today's plan and the holiday situation. */
data class Today(
    val plan: SessionPlan,
    val holiday: HolidayStatus,
    val totalCards: Int,
)

/**
 * The app's single entry point to study logic. Screens call this; it reads and writes the
 * [StudyStore], asks the [MemoryScheduler] for new memory states and the [WorkloadManager]
 * for today's plan. It holds no state of its own apart from a change counter.
 *
 * Every write goes through here, and bumps [changes], so screens can reload when anything
 * changes.
 */
class StudyService(
    private val store: StudyStore,
    private val scheduler: MemoryScheduler,
    private val settings: WorkloadSettings = WorkloadSettings(),
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val dayStartHour: Int = 4,
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val manager = WorkloadManager(settings, scheduler)
    private val writeLock = Mutex()
    private val _changes = MutableStateFlow(0L)

    /** Increases after every write. Screens collect it to know when to reload. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun clock(): StudyDayClock = StudyDayClock(timeZone(), dayStartHour)

    fun currentTime(): Instant = now()

    fun studyDay(): LocalDate = clock().studyDayOf(now())

    private suspend fun <T> write(block: suspend () -> T): T {
        val result = writeLock.withLock { block() }
        _changes.update { it + 1 }
        return result
    }

    // Today and sessions

    suspend fun today(size: SessionSize = SessionSize.SUGGESTED): Today {
        val at = now()
        val holidays = store.holidays()
        val clock = clock()
        val plan = manager.plan(buildRequest(at, clock, holidays, size))
        return Today(
            plan = plan,
            holiday = HolidayRules.statusOn(clock.studyDayOf(at), holidays),
            totalCards = store.allMemoryStates().size,
        )
    }

    /**
     * Starts a sitting. This is the moment the plan counts: recovery progress is saved and
     * the cards it leaves out are recorded for the fairness guarantee. Merely looking at the
     * Home screen changes nothing.
     */
    suspend fun startSession(size: SessionSize = SessionSize.SUGGESTED): ReviewSession = write {
        val at = now()
        val plan = manager.plan(buildRequest(at, clock(), store.holidays(), size))
        store.saveRecoveryState(plan.nextRecoveryState)
        if (plan.queue.isNotEmpty() && plan.deferred.isNotEmpty()) {
            val ids = plan.deferred.map { it.cardId }
            val before = store.deferralStates(ids)
            val after = DeferralLedger.recordSessionStart(before, plan)
            store.saveDeferralStates(after.filterKeys { it in ids && after[it] != before[it] })
        }
        ReviewSession(plan, settings.learnAheadMinutes.minutes)
    }

    /** Applies one real answer and returns the card's new memory state. */
    suspend fun answer(cardId: CardId, rating: Rating, durationMillis: Long?): MemoryState = write {
        val before = store.memoryState(cardId) ?: error("Card $cardId does not exist")
        val at = now()
        val after = scheduler.review(before, rating, at)
        store.applyReview(ReviewRecord(cardId, rating, at, durationMillis, before, after))
        after
    }

    /** When the card would next be due for each answer, for the answer buttons. */
    suspend fun previews(cardId: CardId): Map<Rating, Instant> {
        val state = store.memoryState(cardId) ?: return emptyMap()
        return scheduler.preview(state, now())
    }

    private suspend fun buildRequest(
        at: Instant,
        clock: StudyDayClock,
        holidays: List<HolidayPeriod>,
        size: SessionSize,
    ): WorkloadRequest {
        val today = clock.studyDayOf(at)
        val endOfToday = clock.endOf(today)
        val due = store.dueCards(before = endOfToday)
        val deferrals = store.deferralStates(due.map { it.first })
        val todays = store.reviewsSince(clock.startOf(today))
        val lastStudyDay = store.lastReviewTime()?.let { clock.studyDayOf(it) }
        val forecastEnd = clock.endOf(today.plus(7, DateTimeUnit.DAY))
        return WorkloadRequest(
            now = at,
            clock = clock,
            dueCards = due.map { (id, m) -> ReviewCandidate(id, m, deferrals[id]) },
            newCards = store.newCardIds(NEW_CARD_LOOKAHEAD).map { NewCardCandidate(it) },
            today = TodayProgress(
                reviewsDone = todays.filter { !it.stateBefore.isNew }.map { it.cardId }.distinct().size,
                newCardsIntroduced = todays.count { it.stateBefore.isNew },
            ),
            lastStudyDay = lastStudyDay,
            recovery = store.recoveryState(),
            holiday = HolidayRules.statusOn(today, holidays),
            pace = PaceEstimate.fromRecentReviews(store.recentDurations(200).reversed()),
            size = size,
            forecastDuePerDay = store.dueTimesBetween(endOfToday, forecastEnd).size / 7.0,
            cameBackFromHoliday = HolidayRules.justEnded(holidays, today, lastStudyDay) != null,
        )
    }

    // Decks and cards

    suspend fun deckSummaries(): List<DeckSummary> = store.deckSummaries(clock().endOf(studyDay()))

    suspend fun deck(id: DeckId): Deck? = store.deck(id)

    suspend fun createDeck(name: String, description: String = ""): Result<DeckId> {
        DeckRules.nameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        return Result.success(write { store.insertDeck(name.trim(), description.trim(), now()) })
    }

    suspend fun renameDeck(id: DeckId, name: String, description: String): Result<Unit> {
        DeckRules.nameProblem(name)?.let { return Result.failure(IllegalArgumentException(it)) }
        write { store.renameDeck(id, name.trim(), description.trim()) }
        return Result.success(Unit)
    }

    suspend fun deleteDeck(id: DeckId) = write { store.deleteDeck(id) }

    suspend fun cards(deckId: DeckId): List<Card> = store.cards(deckId)

    suspend fun card(id: CardId): Card? = store.card(id)

    suspend fun memoryState(id: CardId): MemoryState? = store.memoryState(id)

    suspend fun addCard(deckId: DeckId, content: CardContent): Result<CardId> {
        val problems = content.problems()
        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.first()))
        if (store.deck(deckId) == null) return Result.failure(IllegalArgumentException("That deck no longer exists."))
        return Result.success(write { store.insertCard(deckId, content.normalised(), now()) })
    }

    /** Changes what a card says. Its memory state and history are kept. */
    suspend fun updateCard(id: CardId, content: CardContent): Result<Unit> {
        val problems = content.problems()
        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.first()))
        write { store.updateCardContent(id, content.normalised()) }
        return Result.success(Unit)
    }

    suspend fun setSuspended(id: CardId, suspended: Boolean) = write { store.setSuspended(id, suspended) }

    suspend fun deleteCard(id: CardId) = write { store.deleteCard(id) }

    /** Adds the small starter deck (see [SampleDeck]) and returns its id. */
    suspend fun installSampleDeck(): DeckId = write {
        val at = now()
        val id = store.insertDeck(SampleDeck.NAME, SampleDeck.DESCRIPTION, at)
        SampleDeck.cards.forEach { store.insertCard(id, it, at) }
        id
    }

    // Holiday Mode

    suspend fun holidays(): List<HolidayPeriod> = store.holidays()

    suspend fun holidayStatus(): HolidayStatus = HolidayRules.statusOn(studyDay(), store.holidays())

    /**
     * Schedules a holiday. [startsOn] defaults to today; an indefinite pause has no end.
     * Fails when it would overlap another holiday.
     */
    suspend fun scheduleHoliday(
        mode: HolidayMode,
        startsOn: LocalDate = studyDay(),
        endsOn: LocalDate? = null,
    ): Result<HolidayPeriod> {
        val period = runCatching {
            HolidayPeriod(
                id = "holiday-${now().toEpochMilliseconds()}-${Random.nextInt(0, Int.MAX_VALUE).toString(36)}",
                mode = mode,
                startsOn = startsOn,
                endsOn = if (mode == HolidayMode.INDEFINITE_PAUSE) null else endsOn,
            )
        }.getOrElse { return Result.failure(it) }
        return write {
            HolidayRules.schedule(store.holidays(), period).map { store.saveHolidays(it); period }
        }
    }

    /** Ends the current holiday so today is a normal study day again. */
    suspend fun endHolidayNow() = write {
        store.saveHolidays(HolidayRules.endNow(store.holidays(), studyDay()))
    }

    suspend fun cancelUpcomingHoliday(id: String) = write {
        store.saveHolidays(HolidayRules.cancelUpcoming(store.holidays(), id, studyDay()))
    }

    // Statistics

    suspend fun stats(): StudyStats = StudyStats.compute(
        now = now(),
        clock = clock(),
        states = store.allMemoryStates(),
        recentReviews = store.reviewsSince(clock().startOf(studyDay().plus(-29, DateTimeUnit.DAY))),
        forecastDueTimes = store.dueTimesBetween(
            clock().endOf(studyDay()),
            clock().endOf(studyDay().plus(7, DateTimeUnit.DAY)),
        ),
        dueToday = store.dueCards(clock().endOf(studyDay())).size,
    )

    private companion object {
        /** More than any daily new-card limit, so the planner always sees whether any are left. */
        const val NEW_CARD_LOOKAHEAD = 250
    }
}
