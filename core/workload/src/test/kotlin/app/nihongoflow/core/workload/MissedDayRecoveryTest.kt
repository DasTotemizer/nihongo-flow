package app.nihongoflow.core.workload

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.holiday.HolidayRules
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

/**
 * The missed-day scenarios from the spec: 1, 3, 7, 14 and 30 days away, and a return from
 * an indefinite pause. Each runs a realistic collection built up over two months of daily
 * study, then checks the no-guilt guarantees on return.
 */
class MissedDayRecoveryTest {

    private val settings = WorkloadSettings()

    /** Two months of daily study so there is a real mix of young and mature cards. */
    private fun establishedLearner(): StudySimulator =
        StudySimulator(settings, cardCount = 800).also { it.studyDays(60) }

    private fun normalCapacity(sim: StudySimulator) = sim.manager.reviewCapacity(settings.targetSessionMinutes, PaceEstimate.DEFAULT)

    @Test fun `missing one day`() = checkReturn(daysAway = 1, expectRecoveryRamp = false)
    @Test fun `missing three days`() = checkReturn(daysAway = 3, expectRecoveryRamp = true)
    @Test fun `missing seven days`() = checkReturn(daysAway = 7, expectRecoveryRamp = true)
    @Test fun `missing fourteen days`() = checkReturn(daysAway = 14, expectRecoveryRamp = true)
    @Test fun `missing thirty days`() = checkReturn(daysAway = 30, expectRecoveryRamp = true)

    private fun checkReturn(daysAway: Int, expectRecoveryRamp: Boolean) {
        val sim = establishedLearner()
        val capacity = normalCapacity(sim)
        val reviewsBefore = sim.history.size
        val statesBeforeBreak = sim.cards.toMap()

        sim.skipDays(daysAway)

        // Being away creates no reviews and changes no memory state.
        assertEquals(reviewsBefore, sim.history.size, "no reviews may be invented while away")
        assertEquals(statesBeforeBreak, sim.cards.toMap(), "memory state must not change while away")

        // The scheduler sees the real elapsed time: retrievability has dropped.
        val returnTime = sim.clock.startOf(sim.day) + 15.hours
        val sample = statesBeforeBreak.values.filter { !it.isNew }.take(50)
        val lastStudyTime = sim.history.last().reviewedAt
        sample.forEach { s ->
            val rThen = sim.scheduler.retrievability(s, lastStudyTime)!!
            val rNow = sim.scheduler.retrievability(s, returnTime)!!
            assertTrue(rNow <= rThen, "retrievability must reflect real elapsed time")
        }

        val dueOnReturn = sim.dueCountNow()
        val firstPlan = sim.planNow(returnTime)

        // Planning alone does not touch memory state either.
        assertEquals(statesBeforeBreak, sim.cards.toMap())

        // The first session is a comfortable size, whatever the backlog.
        assertTrue(firstPlan.reviewCount <= capacity, "first session had ${firstPlan.reviewCount} reviews, capacity $capacity")
        assertTrue(firstPlan.estimatedMinutes <= settings.maxSessionMinutes, "session estimate ${firstPlan.estimatedMinutes} min")
        // Every due card is either in the session or reported as deferred: none disappear.
        assertEquals(firstPlan.accounting.dueByFsrs, firstPlan.accounting.plannedNow + firstPlan.accounting.deferredByWorkload)
        assertEquals(dueOnReturn, firstPlan.accounting.dueByFsrs)

        if (expectRecoveryRamp) {
            assertEquals(Greeting.WELCOME_BACK_EASE_IN, firstPlan.greeting)
            // Gentle pacing: the first day back is a reduced session.
            assertTrue(firstPlan.reviewCount < capacity, "first day back should ease in")
            if (dueOnReturn > capacity) {
                assertEquals(0, firstPlan.newCount, "new cards pause while there is a backlog")
                assertEquals(NewCardPolicy.PAUSED_FOR_RECOVERY, firstPlan.newCardPolicy)
                assertNotNull(firstPlan.recoveryProgress)
            }
        }

        // Study normally afterwards: every session stays within limits and the learner gets
        // back to normal within a bounded time.
        var backToNormalAfter: Int? = null
        for (d in 1..60) {
            val plan = sim.runDay(study = true)!!
            assertTrue(plan.reviewCount <= capacity, "day $d had ${plan.reviewCount} reviews")
            assertTrue(plan.estimatedMinutes <= settings.maxSessionMinutes)
            if (plan.nextRecoveryState == null && backToNormalAfter == null) backToNormalAfter = d
        }
        val bound = maxOf(5, daysAway * 2)
        val recoveredIn = backToNormalAfter ?: fail("never got back to normal after $daysAway days away")
        assertTrue(recoveredIn <= bound, "took $recoveredIn days to recover from $daysAway days away (bound $bound)")

        // New cards resume once recovered.
        val lastWeek = sim.plans.takeLast(7)
        assertTrue(lastWeek.any { it.newCount > 0 }, "new cards should resume after recovery")

        assertNoFakeReviews(sim)
    }

    @Test
    fun `no due card is deferred forever`() {
        // A learner with a huge backlog and short sessions: fairness must still reach everyone.
        val sim = StudySimulator(WorkloadSettings(targetSessionMinutes = 5, maxSessionMinutes = 8, newCardsPerDay = 10), cardCount = 600)
        sim.studyDays(50)
        sim.skipDays(30)
        val dueAtReturn = sim.dueCandidates(sim.clock.startOf(sim.day) + 15.hours).map { it.cardId }.toSet()
        val returnTime = sim.history.size
        sim.studyDays(45)

        val reviewedAfterReturn = sim.history.drop(returnTime).map { it.cardId }.toSet()
        val neverReviewed = dueAtReturn - reviewedAfterReturn
        assertTrue(neverReviewed.isEmpty(), "${neverReviewed.size} cards due at return were never shown")

        // No card sat in the deferred pile for long: the fairness limit is honoured.
        val worst = sim.deferrals.values.maxOfOrNull { it.studyDaysDeferred } ?: 0
        assertTrue(worst <= sim.settings.fairnessLimitDays + 1, "a card was deferred on $worst study days")
    }

    @Test
    fun `returning from an indefinite pause`() {
        val sim = establishedLearner()
        val pauseStart = sim.day
        sim.holidays += HolidayPeriod("pause", HolidayMode.INDEFINITE_PAUSE, pauseStart, null)
        val before = sim.cards.toMap()
        val reviews = sim.history.size

        // During the pause the app may be opened, but nothing is expected.
        val duringPause = sim.planNow(sim.clock.startOf(sim.day) + 15.hours)
        assertEquals(PlanMode.HOLIDAY, duringPause.mode)
        assertEquals(false, duringPause.suggested)
        assertEquals(false, duringPause.showDailyTarget)
        assertEquals(0, duringPause.newCount)
        assertEquals(Greeting.HOLIDAY_ACTIVE, duringPause.greeting)

        sim.skipDays(21)
        assertEquals(before, sim.cards.toMap())
        assertEquals(reviews, sim.history.size)

        // The learner ends the pause.
        val updated = HolidayRules.endNow(sim.holidays, sim.day)
        sim.holidays.clear(); sim.holidays += updated
        assertEquals(sim.day.plus(-1, DateTimeUnit.DAY), sim.holidays.single().endsOn)

        val first = sim.planNow(sim.clock.startOf(sim.day) + 15.hours)
        assertEquals(PlanMode.RECOVERY, first.mode)
        assertEquals(Greeting.WELCOME_BACK_FROM_HOLIDAY, first.greeting)
        assertTrue(first.suggested)
        assertTrue(first.reviewCount < normalCapacity(sim))
        assertEquals(0, first.newCount)

        val plans = sim.studyDays(40).filterNotNull()
        assertTrue(plans.any { it.nextRecoveryState == null }, "should get back to normal")
        assertNoFakeReviews(sim)
    }

    @Test
    fun `casual holiday offers a short relaxed session`() {
        val sim = establishedLearner()
        sim.holidays += HolidayPeriod("casual", HolidayMode.CASUAL, sim.day, sim.day.plus(6, DateTimeUnit.DAY))
        val plan = sim.planNow(sim.clock.startOf(sim.day) + 15.hours)
        assertEquals(PlanMode.CASUAL_HOLIDAY, plan.mode)
        assertTrue(plan.suggested)
        assertEquals(false, plan.showDailyTarget)
        assertEquals(0, plan.newCount)
        assertTrue(plan.estimatedMinutes <= HolidayRules.CASUAL_SESSION_MINUTES, "casual session was ${plan.estimatedMinutes} min")
    }

    @Test
    fun `holiday deferrals do not count against fairness`() {
        val sim = establishedLearner()
        sim.holidays += HolidayPeriod("casual", HolidayMode.CASUAL, sim.day, sim.day.plus(9, DateTimeUnit.DAY))
        sim.studyDays(10)
        val counted = sim.deferrals.values.maxOfOrNull { it.studyDaysDeferred } ?: 0
        assertEquals(0, counted)
    }

    @Test
    fun `a user can make today's session smaller or larger`() {
        val sim = establishedLearner()
        sim.skipDays(10)
        val at = sim.clock.startOf(sim.day) + 15.hours
        val smaller = sim.planNow(at, SessionSize.SMALLER)
        val suggested = sim.planNow(at, SessionSize.SUGGESTED)
        val larger = sim.planNow(at, SessionSize.LARGER)
        assertTrue(smaller.reviewCount < suggested.reviewCount)
        assertTrue(larger.reviewCount > suggested.reviewCount)
        assertTrue(larger.estimatedMinutes <= settings.maxSessionMinutes)
        // The memory model is identical in all three: only presentation differs.
        assertEquals(smaller.accounting.dueByFsrs, larger.accounting.dueByFsrs)
    }

    @Test
    fun `a normal day needs no recovery`() {
        val sim = establishedLearner()
        val plan = sim.planNow(sim.clock.startOf(sim.day) + 15.hours)
        assertNull(plan.nextRecoveryState)
        assertEquals(Greeting.WELCOME, plan.greeting)
        assertEquals(0, plan.accounting.deferredByWorkload)
    }

    /**
     * Every review in the history is a real answer to a card the learner was shown, in time
     * order per card, and every state chain is unbroken (each review starts from the state
     * the previous one produced).
     */
    private fun assertNoFakeReviews(sim: StudySimulator) {
        val lastStateByCard = mutableMapOf<CardId, MemoryState>()
        for (r in sim.history) {
            lastStateByCard[r.cardId]?.let { prev ->
                assertEquals(prev, r.stateBefore, "review history for card ${r.cardId} has a gap")
                assertTrue(r.reviewedAt >= prev.lastReview!!)
            }
            assertEquals(r.reviewedAt, r.stateAfter.lastReview)
            lastStateByCard[r.cardId] = r.stateAfter
        }
        lastStateByCard.forEach { (id, s) -> assertEquals(s, sim.cards.getValue(id)) }
    }
}
