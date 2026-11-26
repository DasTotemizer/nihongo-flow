package app.nihongoflow.core.workload

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.RetrievabilityEstimator
import app.nihongoflow.core.model.StudyDayClock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Focused unit tests of individual workload rules, with a simple stand-in memory model. */
class WorkloadManagerTest {

    private val clock = StudyDayClock(TimeZone.UTC, dayStartHour = 4)
    private val today = LocalDate(2026, 3, 10) // a Tuesday
    private val now = clock.startOf(today) + 14.hours

    /** Exponential forgetting with the card's stability: enough to rank cards in tests. */
    private val estimator = RetrievabilityEstimator { s, at ->
        val last = s.lastReview ?: return@RetrievabilityEstimator null
        exp(-((at - last).inWholeHours / 24.0) / s.stability!!)
    }

    private fun review(id: Long, dueDaysAgo: Int, stability: Double = 10.0, deferral: DeferralState? = null) =
        ReviewCandidate(
            cardId = id,
            memory = MemoryState(
                phase = MemoryPhase.REVIEW,
                step = null,
                stability = stability,
                difficulty = 5.0,
                due = now - dueDaysAgo.days,
                lastReview = now - dueDaysAgo.days - stability.days,
            ),
            deferral = deferral,
        )

    private fun request(
        due: List<ReviewCandidate>,
        newCards: Int = 20,
        lastStudyDay: LocalDate? = LocalDate(2026, 3, 9),
        holiday: HolidayStatus = HolidayStatus.None,
        recovery: RecoveryState? = null,
        forecast: Double = 0.0,
        today: TodayProgress = TodayProgress(),
        at: Instant = now,
    ) = WorkloadRequest(
        now = at,
        clock = clock,
        dueCards = due,
        newCards = (1000L until 1000L + newCards).map { NewCardCandidate(it) },
        today = today,
        lastStudyDay = lastStudyDay,
        recovery = recovery,
        holiday = holiday,
        forecastDuePerDay = forecast,
    )

    private val manager = WorkloadManager(WorkloadSettings(), estimator)

    @Test
    fun `normal day shows everything due plus new cards`() {
        val plan = manager.plan(request((1L..12L).map { review(it, 0) }))
        assertEquals(PlanMode.NORMAL, plan.mode)
        assertEquals(12, plan.reviewCount)
        assertEquals(5, plan.newCount)
        assertEquals(NewCardPolicy.FULL, plan.newCardPolicy)
        assertTrue(plan.suggested)
        assertNull(plan.nextRecoveryState)
    }

    @Test
    fun `a backlog without a break keeps normal session size and pauses new cards`() {
        val plan = manager.plan(request((1L..50L).map { review(it, 1) }))
        assertEquals(PlanMode.RECOVERY, plan.mode)
        assertEquals(30, plan.reviewCount) // 10 minutes at 20 s per card
        assertEquals(0, plan.newCount)
        assertEquals(NewCardPolicy.PAUSED_FOR_RECOVERY, plan.newCardPolicy)
        assertEquals(Greeting.WELCOME, plan.greeting)
        assertEquals(20, plan.accounting.deferredByWorkload)
        assertTrue(plan.deferred.all { it.reason == DeferralReason.SESSION_LIMIT })
    }

    @Test
    fun `learning steps come first`() {
        val learning = ReviewCandidate(
            99L,
            MemoryState(MemoryPhase.RELEARNING, 0, 2.0, 7.0, now - 5.minutes, now - 15.minutes),
        )
        val plan = manager.plan(request(listOf(learning) + (1L..40L).map { review(it, 2) }))
        assertEquals(99L, plan.queue.first().cardId)
        assertEquals(PlanReason.LEARNING_STEP, plan.queue.first().reason)
    }

    @Test
    fun `fast-fading memories are prioritised over long-forgotten and still-strong ones`() {
        val fading = review(1L, dueDaysAgo = 1, stability = 3.0) // losing recall quickly right now
        val forgotten = review(2L, dueDaysAgo = 60, stability = 1.0) // R already near zero
        val strong = review(3L, dueDaysAgo = 0, stability = 200.0) // barely changes in a day
        val small = WorkloadManager(WorkloadSettings(targetSessionMinutes = 1, maxSessionMinutes = 1), estimator)
        // 1 minute = 3 cards; make only one slot available by marking two reviews done today.
        val plan = small.plan(request(listOf(forgotten, strong, fading), today = TodayProgress(reviewsDone = 2)))
        assertEquals(listOf(1L), plan.queue.filter { it.kind == CardKind.REVIEW }.map { it.cardId })
    }

    @Test
    fun `cards deferred too often get a guaranteed place`() {
        val waited = review(7L, dueDaysAgo = 10, stability = 1.0, deferral = DeferralState(LocalDate(2026, 3, 1), LocalDate(2026, 3, 9), 5))
        val others = (1L..6L).map { review(it, 1, 3.0) } + (8L..40L).map { review(it, 1, 3.0) }
        val plan = manager.plan(request(others + waited))
        val first = plan.queue.first { it.kind == CardKind.REVIEW }
        assertEquals(7L, first.cardId)
        assertEquals(PlanReason.WAITED_LONGEST, first.reason)
    }

    @Test
    fun `deferral counts once per study day and clears on review`() {
        val plan = manager.plan(request((1L..50L).map { review(it, 1) }))
        var states = DeferralLedger.recordSessionStart(emptyMap(), plan)
        assertEquals(20, states.size)
        assertTrue(states.values.all { it.studyDaysDeferred == 1 })
        states = DeferralLedger.recordSessionStart(states, plan) // second session the same day
        assertTrue(states.values.all { it.studyDaysDeferred == 1 })
        val id = states.keys.first()
        states = DeferralLedger.recordReviewed(states, id)
        assertTrue(id !in states)
    }

    @Test
    fun `full holiday suggests nothing but still allows a short session`() {
        val trip = HolidayPeriod("t", HolidayMode.FULL, LocalDate(2026, 3, 8), LocalDate(2026, 3, 20))
        val plan = manager.plan(request((1L..80L).map { review(it, 3) }, holiday = HolidayStatus.Active(trip)))
        assertEquals(PlanMode.HOLIDAY, plan.mode)
        assertTrue(!plan.suggested)
        assertTrue(!plan.showDailyTarget)
        assertEquals(0, plan.newCount)
        assertEquals(NewCardPolicy.PAUSED_FOR_HOLIDAY, plan.newCardPolicy)
        assertTrue(plan.estimatedMinutes <= 5)
        assertTrue(plan.deferred.all { it.reason == DeferralReason.HOLIDAY })
    }

    @Test
    fun `rest days suggest nothing and add no new cards`() {
        val weekdaysOnly = WorkloadManager(
            WorkloadSettings(preferredStudyDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
            estimator,
        )
        val plan = weekdaysOnly.plan(request((1L..10L).map { review(it, 0) }))
        assertEquals(PlanMode.REST_DAY, plan.mode)
        assertTrue(!plan.suggested)
        assertEquals(0, plan.newCount)
        assertEquals(10, plan.reviewCount) // still available if the learner wants
    }

    @Test
    fun `new cards adapt to a busy forecast`() {
        val due = (1L..10L).map { review(it, 0) }
        assertEquals(5, manager.plan(request(due, forecast = 5.0)).newCount)
        val busy = manager.plan(request(due, forecast = 25.0)) // 5 of 30 free: reduced
        assertEquals(NewCardPolicy.REDUCED, busy.newCardPolicy)
        assertTrue(busy.newCount in 1..4)
        val full = manager.plan(request(due, forecast = 31.0))
        assertEquals(NewCardPolicy.PAUSED_FOR_WORKLOAD, full.newCardPolicy)
        assertEquals(0, full.newCount)
    }

    @Test
    fun `daily new card limit is respected across sessions`() {
        val plan = manager.plan(request(emptyList(), today = TodayProgress(newCardsIntroduced = 5)))
        assertEquals(0, plan.newCount)
        assertEquals(NewCardPolicy.DAILY_LIMIT_REACHED, plan.newCardPolicy)
    }

    @Test
    fun `when today's suggested reviews are done the plan says so kindly`() {
        val plan = manager.plan(request((1L..50L).map { review(it, 1) }, today = TodayProgress(reviewsDone = 30, newCardsIntroduced = 5)))
        assertEquals(PlanMode.DONE_FOR_TODAY, plan.mode)
        assertEquals(Greeting.ALL_DONE_TODAY, plan.greeting)
        // Asking for more still works.
        val more = manager.plan(
            request((1L..50L).map { review(it, 1) }, today = TodayProgress(reviewsDone = 30, newCardsIntroduced = 5))
                .copy(size = SessionSize.LARGER),
        )
        assertEquals(15, more.reviewCount)
    }

    @Test
    fun `a backlog that cannot shrink is flagged instead of growing silently`() {
        val plan = manager.plan(request((1L..100L).map { review(it, 1) }, forecast = 40.0))
        val progress = plan.recoveryProgress!!
        assertTrue(progress.needsLargerSessions)
        assertNull(progress.estimatedStudyDays)
    }

    @Test
    fun `nothing due and no new cards`() {
        val plan = manager.plan(request(emptyList(), newCards = 0))
        assertEquals(PlanMode.NOTHING_DUE, plan.mode)
        assertEquals(0, plan.estimatedMinutes)
    }

    @Test
    fun `pace estimate uses the median of real answer times`() {
        assertEquals(PaceEstimate.DEFAULT, PaceEstimate.fromRecentReviews(List(5) { 8_000L }))
        val pace = PaceEstimate.fromRecentReviews(List(30) { 8_000L } + List(10) { 600_000L })
        assertEquals(8.0, pace.secondsPerReview)
    }

    @Test
    fun `study day starts at the configured hour`() {
        val lateNight = clock.startOf(today) - 1.hours // 3 AM on the 10th
        assertEquals(LocalDate(2026, 3, 9), clock.studyDayOf(lateNight))
        assertEquals(today, clock.studyDayOf(clock.startOf(today)))
    }
}
