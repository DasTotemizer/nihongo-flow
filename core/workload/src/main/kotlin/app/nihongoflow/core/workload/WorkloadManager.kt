package app.nihongoflow.core.workload

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.RetrievabilityEstimator
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Decides which due cards are presented in a session.
 *
 * This is deliberately separate from the memory scheduler. It reads FSRS state to judge
 * priority, but it never changes stability, difficulty, due dates or review history, and it
 * never pretends a card was reviewed. A card can be overdue according to FSRS and still be
 * left for a later session; it stays eligible and is reported as deferred.
 *
 * The planner is a pure function of its inputs, so it can be tested day by day and reused
 * by any client.
 */
class WorkloadManager(
    private val settings: WorkloadSettings,
    private val memory: RetrievabilityEstimator,
) {

    fun plan(request: WorkloadRequest): SessionPlan {
        val today = request.clock.studyDayOf(request.now)
        val endOfToday = request.clock.endOf(today)
        val learnAheadUntil = request.now + settings.learnAheadMinutes.minutes

        // 1. What FSRS says is due. Nothing here alters memory state.
        val learning = request.dueCards
            .filter { it.memory.phase == MemoryPhase.LEARNING || it.memory.phase == MemoryPhase.RELEARNING }
            .filter { it.memory.due <= learnAheadUntil }
            .sortedWith(compareBy({ it.memory.due }, { it.cardId }))
        val reviews = request.dueCards
            .filter { it.memory.phase == MemoryPhase.REVIEW && it.memory.due < endOfToday }
        val dueByFsrs = learning.size + reviews.size

        val normalCapacity = reviewCapacity(settings.targetSessionMinutes, request.pace)

        // 2. Is this a return after a break, or a backlog bigger than a comfortable day?
        val daysAway = request.lastStudyDay?.daysUntil(today)
        val recoveryBefore = advanceRecovery(request.recovery, request.lastStudyDay, today)
        val returningAfterBreak = daysAway != null && daysAway >= RETURN_AFTER_DAYS
        val backlogTooBig = dueByFsrs > normalCapacity
        val holidayActive = request.holiday is HolidayStatus.Active

        var recovery: RecoveryState? = when {
            holidayActive -> recoveryBefore
            // A fresh break restarts the ramp.
            returningAfterBreak && recoveryBefore?.startedOn != today ->
                RecoveryState(startedOn = today, backlogAtStart = dueByFsrs, easeIn = true)
            recoveryBefore != null -> recoveryBefore
            backlogTooBig -> RecoveryState(startedOn = today, backlogAtStart = dueByFsrs, easeIn = false)
            else -> null
        }
        val rampFactor = recovery?.takeIf { it.easeIn }
            ?.let { settings.recoveryPacing.factorFor(it.studyDaysCompleted) } ?: 1.0
        if (recovery != null && !holidayActive && !backlogTooBig && rampFactor >= 1.0) {
            recovery = null // back to normal
        }

        // 3. How many reviews fit today.
        val holidayMode = (request.holiday as? HolidayStatus.Active)?.period?.mode
        val restDay = holidayMode == null && today.dayOfWeek !in settings.preferredStudyDays
        val dailyBudget = when {
            holidayMode != null -> reviewCapacity(settings.casualSessionMinutes, request.pace)
            else -> maxOf(1, (normalCapacity * rampFactor).roundToInt())
        }
        val remainingToday = maxOf(0, dailyBudget - request.today.reviewsDone)
        val sessionCap = minOf(settings.maxReviewsPerSession, reviewCapacity(settings.maxSessionMinutes, request.pace))
        val reviewSlots = when (request.size) {
            SessionSize.SMALLER -> ceil(minOf(remainingToday, dueByFsrs) / 2.0).toInt()
            SessionSize.SUGGESTED -> remainingToday
            SessionSize.LARGER -> remainingToday + ceil(normalCapacity / 2.0).toInt()
        }.coerceAtMost(if (holidayMode != null) dailyBudget else sessionCap)

        // 4. Pick reviews: learning steps first, then fairness, then forgetting risk.
        val chosenLearning = learning.take(reviewSlots)
        val ranked = rankReviews(reviews, request, today)
        val chosenReviews = ranked.take(maxOf(0, reviewSlots - chosenLearning.size))
        val chosenIds = (chosenLearning.map { it.cardId } + chosenReviews.map { it.first.cardId }).toSet()

        // 5. New cards adapt to the situation.
        val (newPolicy, newAllowed) = newCardAllowance(
            request = request,
            holidayActive = holidayActive,
            restDay = restDay,
            recoveryActive = recovery != null,
            backlogTooBig = backlogTooBig,
            rampFactor = rampFactor,
            normalCapacity = normalCapacity,
        )
        val reviewSeconds = chosenIds.size * request.pace.secondsPerReview
        val secondsLeft = settings.maxSessionMinutes * 60 - reviewSeconds
        val newFitByTime = maxOf(0, floor(secondsLeft / request.pace.secondsPerNewCard).toInt())
        val newCount = when (request.size) {
            SessionSize.SMALLER -> minOf(newAllowed, newFitByTime, ceil(newAllowed / 2.0).toInt())
            else -> minOf(newAllowed, newFitByTime)
        }
        val chosenNew = request.newCards.take(newCount)

        // 6. Order: learning steps, then reviews with new cards spread evenly among them.
        val reviewItems = chosenReviews.map { (c, reason) -> PlannedCard(c.cardId, CardKind.REVIEW, reason) }
        val newItems = chosenNew.map { PlannedCard(it.cardId, CardKind.NEW, PlanReason.NEW_CARD) }
        val queue = chosenLearning.map { PlannedCard(it.cardId, CardKind.LEARNING, PlanReason.LEARNING_STEP) } +
            interleave(reviewItems, newItems)

        // 7. Everything due but not chosen stays eligible and is reported as deferred.
        val deferralReason = when {
            holidayActive -> DeferralReason.HOLIDAY
            restDay -> DeferralReason.REST_DAY
            else -> DeferralReason.SESSION_LIMIT
        }
        val deferred = (learning.map { it.cardId } + ranked.map { it.first.cardId })
            .filter { it !in chosenIds }
            .map { DeferredCard(it, deferralReason) }

        val estimatedSeconds = chosenIds.size * request.pace.secondsPerReview +
            chosenNew.size * request.pace.secondsPerNewCard
        val estimatedMinutes = if (queue.isEmpty()) 0 else maxOf(1, ceil(estimatedSeconds / 60.0).toInt())

        val mode = when {
            holidayMode == HolidayMode.CASUAL -> PlanMode.CASUAL_HOLIDAY
            holidayMode != null -> PlanMode.HOLIDAY
            restDay -> PlanMode.REST_DAY
            queue.isEmpty() && dueByFsrs == 0 && request.today.reviewsDone == 0 && request.newCards.isEmpty() -> PlanMode.NOTHING_DUE
            queue.isEmpty() -> PlanMode.DONE_FOR_TODAY
            recovery != null -> PlanMode.RECOVERY
            else -> PlanMode.NORMAL
        }

        val progress = recovery?.let {
            recoveryProgress(it, deferred.size, normalCapacity, request.forecastDuePerDay)
        }

        return SessionPlan(
            day = today,
            mode = mode,
            greeting = greetingFor(mode, returningAfterBreak, recovery, request, today),
            queue = queue,
            suggested = mode == PlanMode.NORMAL || mode == PlanMode.RECOVERY || mode == PlanMode.CASUAL_HOLIDAY,
            showDailyTarget = mode == PlanMode.NORMAL || mode == PlanMode.RECOVERY || mode == PlanMode.DONE_FOR_TODAY,
            estimatedMinutes = estimatedMinutes,
            newCardPolicy = if (chosenNew.isEmpty() && newPolicy == NewCardPolicy.FULL) NewCardPolicy.NONE_AVAILABLE else newPolicy,
            deferred = deferred,
            accounting = DueAccounting(
                dueByFsrs = dueByFsrs,
                plannedNow = chosenIds.size,
                deferredByWorkload = deferred.size,
                reviewedToday = request.today.reviewsDone,
            ),
            recoveryProgress = progress,
            nextRecoveryState = recovery,
        )
    }

    /** Number of reviews that fit in [minutes], at least one. */
    fun reviewCapacity(minutes: Int, pace: PaceEstimate): Int =
        maxOf(1, minOf(settings.maxReviewsPerSession, floor(minutes * 60 / pace.secondsPerReview).toInt()))

    /**
     * Review-phase cards in priority order, each with its reason.
     *
     * First, cards that sessions have already left out on [WorkloadSettings.fairnessLimitDays]
     * or more study days, oldest first: this guarantees every due card is eventually shown.
     * Then the rest, by how much recall probability the card would lose if it waited one
     * more day (R(now) - R(now + 1 day)). That favours memories that are fading fastest right
     * now, rather than cards that are long forgotten or still comfortably remembered.
     */
    private fun rankReviews(
        reviews: List<ReviewCandidate>,
        request: WorkloadRequest,
        today: LocalDate,
    ): List<Pair<ReviewCandidate, PlanReason>> {
        val startOfToday = request.clock.startOf(today)
        val (waited, others) = reviews.partition {
            (it.deferral?.studyDaysDeferred ?: 0) >= settings.fairnessLimitDays
        }
        val waitedSorted = waited
            .sortedWith(compareBy({ it.deferral!!.firstDeferredOn }, { it.memory.due }, { it.cardId }))
            .map { it to PlanReason.WAITED_LONGEST }
        val tomorrow = request.now + 1.days
        val othersSorted = others
            .map { c ->
                val rNow = memory.retrievability(c.memory, request.now) ?: 0.0
                val rTomorrow = memory.retrievability(c.memory, tomorrow) ?: 0.0
                c to (rNow - rTomorrow)
            }
            .sortedWith(
                compareByDescending<Pair<ReviewCandidate, Double>> { it.second }
                    .thenBy { it.first.memory.due }
                    .thenBy { it.first.cardId },
            )
            .map { (c, _) ->
                c to if (c.memory.due < startOfToday) PlanReason.FORGETTING_RISK else PlanReason.DUE_TODAY
            }
        return waitedSorted + othersSorted
    }

    private fun newCardAllowance(
        request: WorkloadRequest,
        holidayActive: Boolean,
        restDay: Boolean,
        recoveryActive: Boolean,
        backlogTooBig: Boolean,
        rampFactor: Double,
        normalCapacity: Int,
    ): Pair<NewCardPolicy, Int> {
        val leftToday = maxOf(0, settings.newCardsPerDay - request.today.newCardsIntroduced)
        if (settings.newCardsPerDay == 0 || request.newCards.isEmpty()) return NewCardPolicy.NONE_AVAILABLE to 0
        if (holidayActive) return NewCardPolicy.PAUSED_FOR_HOLIDAY to 0
        if (restDay) return NewCardPolicy.PAUSED_FOR_WORKLOAD to 0
        if (recoveryActive && backlogTooBig) return NewCardPolicy.PAUSED_FOR_RECOVERY to 0
        if (leftToday == 0) return NewCardPolicy.DAILY_LIMIT_REACHED to 0

        // Each new card adds several reviews over the following weeks. If the coming days
        // already fill most of a normal session, introduce fewer new cards.
        val headroom = (normalCapacity - request.forecastDuePerDay) / normalCapacity
        val workloadFactor = when {
            headroom <= 0.0 -> return NewCardPolicy.PAUSED_FOR_WORKLOAD to 0
            headroom < WORKLOAD_HEADROOM -> headroom / WORKLOAD_HEADROOM
            else -> 1.0
        }
        val factor = minOf(rampFactor, workloadFactor)
        val allowed = floor(leftToday * factor).toInt()
        return when {
            factor >= 1.0 -> NewCardPolicy.FULL to leftToday
            allowed == 0 && recoveryActive -> NewCardPolicy.PAUSED_FOR_RECOVERY to 0
            allowed == 0 -> NewCardPolicy.PAUSED_FOR_WORKLOAD to 0
            else -> NewCardPolicy.REDUCED to allowed
        }
    }

    /** Counts a completed study day toward the ramp, once per day. */
    private fun advanceRecovery(state: RecoveryState?, lastStudyDay: LocalDate?, today: LocalDate): RecoveryState? {
        if (state == null || lastStudyDay == null) return state
        val alreadyCounted = state.lastCountedStudyDay != null && lastStudyDay <= state.lastCountedStudyDay
        if (lastStudyDay >= today || lastStudyDay < state.startedOn || alreadyCounted) return state
        return state.copy(
            studyDaysCompleted = state.studyDaysCompleted + 1,
            lastCountedStudyDay = lastStudyDay,
        )
    }

    private fun recoveryProgress(
        state: RecoveryState,
        remainingAfterSession: Int,
        normalCapacity: Int,
        forecastDuePerDay: Double,
    ): RecoveryProgress {
        val fraction = if (state.backlogAtStart <= 0) 1.0
        else (1.0 - remainingAfterSession.toDouble() / state.backlogAtStart).coerceIn(0.0, 1.0)
        val netPerDay = normalCapacity - forecastDuePerDay
        val days = when {
            remainingAfterSession == 0 -> 0
            netPerDay <= 0.0 -> null
            else -> ceil(remainingAfterSession / netPerDay).toInt()
        }
        return RecoveryProgress(
            fraction = fraction,
            remainingAfterSession = remainingAfterSession,
            estimatedStudyDays = days,
            needsLargerSessions = remainingAfterSession > 0 && netPerDay <= 0.0,
        )
    }

    private fun greetingFor(
        mode: PlanMode,
        returningAfterBreak: Boolean,
        recovery: RecoveryState?,
        request: WorkloadRequest,
        today: LocalDate,
    ): Greeting = when (mode) {
        PlanMode.HOLIDAY -> Greeting.HOLIDAY_ACTIVE
        PlanMode.CASUAL_HOLIDAY -> Greeting.CASUAL_HOLIDAY
        PlanMode.REST_DAY -> Greeting.REST_DAY
        PlanMode.DONE_FOR_TODAY -> Greeting.ALL_DONE_TODAY
        PlanMode.NOTHING_DUE -> Greeting.NOTHING_DUE
        PlanMode.RECOVERY, PlanMode.NORMAL -> when {
            request.today.reviewsDone == 0 && returningAfterBreak && request.cameBackFromHoliday ->
                Greeting.WELCOME_BACK_FROM_HOLIDAY
            request.today.reviewsDone == 0 && returningAfterBreak -> Greeting.WELCOME_BACK_EASE_IN
            recovery != null && recovery.startedOn != today -> Greeting.RECOVERY_CONTINUES
            recovery != null && recovery.easeIn -> Greeting.WELCOME_BACK_EASE_IN
            else -> Greeting.WELCOME
        }
    }

    private fun <T> interleave(reviews: List<T>, news: List<T>): List<T> {
        if (news.isEmpty()) return reviews
        if (reviews.isEmpty()) return news
        val result = ArrayList<T>(reviews.size + news.size)
        val every = maxOf(1, reviews.size / (news.size + 1))
        var n = 0
        reviews.forEachIndexed { i, r ->
            result += r
            if ((i + 1) % every == 0 && n < news.size) result += news[n++]
        }
        while (n < news.size) result += news[n++]
        return result
    }

    companion object {
        /** A gap of this many days since the last study day counts as returning from a break. */
        const val RETURN_AFTER_DAYS = 3

        /** New cards are reduced when the coming days leave less than this share of a session free. */
        const val WORKLOAD_HEADROOM = 0.3
    }
}
