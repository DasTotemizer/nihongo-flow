package app.nihongoflow.core.workload

import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.holiday.HolidayRules
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.model.StudyDayClock
import app.nihongoflow.core.scheduler.FsrsScheduler
import app.nihongoflow.core.scheduler.SchedulerConfig
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Day-by-day simulation of a learner using the real FSRS scheduler and the real workload
 * manager. The simulated learner recalls a card with probability equal to its FSRS
 * retrievability, which is the model's own assumption.
 *
 * The simulator records everything the tests need to check the no-guilt guarantees: every
 * plan, every real review, and the memory state of every card before and after planning.
 */
class StudySimulator(
    val settings: WorkloadSettings = WorkloadSettings(),
    cardCount: Int = 800,
    seed: Int = 7,
    val startDay: LocalDate = LocalDate(2026, 1, 5),
) {
    val clock = StudyDayClock(TimeZone.UTC, dayStartHour = 4)
    val scheduler = FsrsScheduler(SchedulerConfig())
    val manager = WorkloadManager(settings, scheduler)
    private val random = Random(seed)

    val cards: MutableMap<CardId, MemoryState> = (1L..cardCount).associateWith {
        MemoryState.new(clock.startOf(startDay))
    }.toMutableMap()
    private val newOrder = ArrayDeque((1L..cardCount).toList())

    val history = mutableListOf<ReviewRecord>()
    val plans = mutableListOf<SessionPlan>()
    var deferrals: Map<CardId, DeferralState> = emptyMap()
    var recovery: RecoveryState? = null
    var lastStudyDay: LocalDate? = null
    val holidays = mutableListOf<HolidayPeriod>()

    /** Days on which a session was planned, with the plan, keyed by day. */
    val planByDay = linkedMapOf<LocalDate, SessionPlan>()

    var day: LocalDate = startDay
        private set

    fun dueCandidates(at: Instant): List<ReviewCandidate> {
        val endOfToday = clock.endOf(clock.studyDayOf(at))
        return cards.filter { (_, m) -> !m.isNew && m.due < endOfToday }
            .map { (id, m) -> ReviewCandidate(id, m, deferrals[id]) }
    }

    fun forecastDuePerDay(at: Instant): Double {
        val today = clock.studyDayOf(at)
        val from = clock.endOf(today)
        val to = clock.endOf(today.plus(7, DateTimeUnit.DAY))
        return cards.values.count { !it.isNew && it.due >= from && it.due < to } / 7.0
    }

    fun planNow(at: Instant, size: SessionSize = SessionSize.SUGGESTED, reviewsDone: Int = 0, newDone: Int = 0): SessionPlan {
        val today = clock.studyDayOf(at)
        return manager.plan(
            WorkloadRequest(
                now = at,
                clock = clock,
                dueCards = dueCandidates(at),
                newCards = newOrder.map { NewCardCandidate(it) },
                today = TodayProgress(reviewsDone, newDone),
                lastStudyDay = lastStudyDay,
                recovery = recovery,
                holiday = HolidayRules.statusOn(today, holidays),
                forecastDuePerDay = forecastDuePerDay(at),
                cameBackFromHoliday = HolidayRules.justEnded(holidays, today, lastStudyDay) != null,
                size = size,
            ),
        )
    }

    /** Runs one day. When [study] is false the learner does not open the app at all. */
    fun runDay(study: Boolean, size: SessionSize = SessionSize.SUGGESTED): SessionPlan? {
        val sessionStart = clock.startOf(day) + 15.hours // 7 PM
        var plan: SessionPlan? = null
        if (study) {
            plan = planNow(sessionStart, size)
            planByDay[day] = plan
            plans += plan
            recovery = plan.nextRecoveryState
            if (plan.queue.isNotEmpty()) {
                deferrals = DeferralLedger.recordSessionStart(deferrals, plan)
                answerAll(plan, sessionStart)
            }
            // Learning steps due within the evening are finished in a short second pass,
            // as a learner naturally would before closing the app.
            var t = sessionStart + 1.hours
            repeat(3) {
                val pending = cards.filter { (_, m) ->
                    (m.phase == MemoryPhase.LEARNING || m.phase == MemoryPhase.RELEARNING) && m.due <= t
                }.keys
                pending.forEach { review(it, t) }
                t += 1.hours
            }
        }
        day = day.plus(1, DateTimeUnit.DAY)
        return plan
    }

    private fun answerAll(plan: SessionPlan, start: Instant) {
        var t = start
        for (item in plan.queue) {
            if (item.kind == CardKind.NEW) newOrder.remove(item.cardId)
            review(item.cardId, t)
            t += 20.seconds
        }
    }

    private fun review(id: CardId, at: Instant) {
        val before = cards.getValue(id)
        val r = scheduler.retrievability(before, at)
        val rating = when {
            r == null -> if (random.nextDouble() < 0.7) Rating.GOOD else Rating.AGAIN
            random.nextDouble() < r -> if (random.nextDouble() < 0.15) Rating.EASY else Rating.GOOD
            else -> Rating.AGAIN
        }
        val after = scheduler.review(before, rating, at)
        cards[id] = after
        history += ReviewRecord(id, rating, at, 20_000, before, after)
        deferrals = DeferralLedger.recordReviewed(deferrals, id)
        lastStudyDay = clock.studyDayOf(at)
    }

    fun studyDays(n: Int, size: SessionSize = SessionSize.SUGGESTED) = (1..n).map { runDay(true, size) }
    fun skipDays(n: Int) = repeat(n) { runDay(false) }

    fun dueCountNow(): Int = dueCandidates(clock.startOf(day) + 15.hours).size
}
