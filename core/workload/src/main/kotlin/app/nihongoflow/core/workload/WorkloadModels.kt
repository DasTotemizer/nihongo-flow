package app.nihongoflow.core.workload

import app.nihongoflow.core.holiday.HolidayRules
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.StudyDayClock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** How quickly a returning learner is brought back to their normal session size. */
enum class RecoveryPacing(val rampFactors: List<Double>) {
    /** Four study days: 60%, 75%, 90%, then 100% of a normal session. */
    GENTLE(listOf(0.6, 0.75, 0.9, 1.0)),

    /** Two study days: 80%, then 100%. */
    STEADY(listOf(0.8, 1.0)),

    /** Normal session size straight away. The backlog is still spread over several days. */
    BRISK(listOf(1.0));

    fun factorFor(rampDay: Int): Double = rampFactors[rampDay.coerceIn(0, rampFactors.lastIndex)]
    val rampLength: Int get() = rampFactors.size
}

/**
 * The learner's workload preferences. Every value is configurable in Settings; the defaults
 * aim for a comfortable session rather than a catch-up marathon.
 */
data class WorkloadSettings(
    /** Normal review time for a day, in minutes. The suggested session is 5 to 15 minutes. */
    val targetSessionMinutes: Int = 10,
    /** Hard cap on a session's estimated length, including new cards. */
    val maxSessionMinutes: Int = 15,
    val maxReviewsPerSession: Int = 60,
    val newCardsPerDay: Int = 5,
    val recoveryPacing: RecoveryPacing = RecoveryPacing.GENTLE,
    /** Days the learner plans to study. On other days nothing is suggested, but studying is allowed. */
    val preferredStudyDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /**
     * A due card that has been left out of this many sessions on different study days gets
     * a guaranteed place in the next one, so no card is deferred forever.
     */
    val fairnessLimitDays: Int = 5,
    val casualSessionMinutes: Int = HolidayRules.CASUAL_SESSION_MINUTES,
    /** Learning-step cards due within this many minutes are included early. */
    val learnAheadMinutes: Int = 20,
) {
    init {
        require(targetSessionMinutes in 1..120) { "targetSessionMinutes must be 1..120" }
        require(maxSessionMinutes >= targetSessionMinutes) { "maxSessionMinutes must be at least targetSessionMinutes" }
        require(maxReviewsPerSession >= 1) { "maxReviewsPerSession must be at least 1" }
        require(newCardsPerDay in 0..200) { "newCardsPerDay must be 0..200" }
        require(fairnessLimitDays >= 1) { "fairnessLimitDays must be at least 1" }
        require(preferredStudyDays.isNotEmpty()) { "Choose at least one study day" }
    }
}

/** How long the learner usually takes, used to turn minutes into a number of cards. */
data class PaceEstimate(val secondsPerReview: Double, val secondsPerNewCard: Double) {
    companion object {
        val DEFAULT = PaceEstimate(secondsPerReview = 20.0, secondsPerNewCard = 45.0)
        private const val MIN_SAMPLES = 20
        private const val MAX_SAMPLES = 200

        /**
         * Median answer time of recent real reviews. Answers longer than five minutes (the
         * learner probably walked away) are ignored. Falls back to [DEFAULT] when there is
         * too little history.
         */
        fun fromRecentReviews(durationsMillis: List<Long>): PaceEstimate {
            val usable = durationsMillis.filter { it in 1_000L..300_000L }.takeLast(MAX_SAMPLES)
            if (usable.size < MIN_SAMPLES) return DEFAULT
            val sorted = usable.sorted()
            val mid = sorted.size / 2
            val medianMs = if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid].toDouble()
            val review = (medianMs / 1000.0).coerceIn(5.0, 60.0)
            return PaceEstimate(review, (review * 2.5).coerceIn(20.0, 120.0))
        }
    }
}

/** A card that FSRS says is due (or a learning step that is due soon). */
data class ReviewCandidate(
    val cardId: CardId,
    val memory: MemoryState,
    val deferral: DeferralState? = null,
)

/** A card not yet introduced, in the order the learner's decks want them introduced. */
data class NewCardCandidate(val cardId: CardId)

/**
 * Presentation bookkeeping for a due card that sessions have left out. Stored apart from
 * the card's memory state, and cleared when the card is reviewed.
 */
data class DeferralState(
    val firstDeferredOn: LocalDate,
    val lastDeferredOn: LocalDate,
    /** Number of different study days on which a session left this card out. */
    val studyDaysDeferred: Int,
)

/**
 * Persisted state of a gradual return. Created when the learner comes back after a break
 * or has more due than a normal session holds; removed when things are back to normal.
 */
data class RecoveryState(
    val startedOn: LocalDate,
    val backlogAtStart: Int,
    /**
     * True when this recovery began with a return after a break, so sessions ease in
     * gradually. A backlog without a break (for example after one missed day) keeps normal
     * session sizes and just spreads the extra cards.
     */
    val easeIn: Boolean = true,
    /** Completed study days since recovery started; drives the ramp. */
    val studyDaysCompleted: Int = 0,
    val lastCountedStudyDay: LocalDate? = null,
)

data class TodayProgress(val reviewsDone: Int = 0, val newCardsIntroduced: Int = 0)

/** The learner's optional adjustment of today's session. */
enum class SessionSize { SMALLER, SUGGESTED, LARGER }

data class WorkloadRequest(
    val now: Instant,
    val clock: StudyDayClock,
    /** Cards whose FSRS due time is on or before the end of today's study day. */
    val dueCards: List<ReviewCandidate>,
    val newCards: List<NewCardCandidate>,
    val today: TodayProgress = TodayProgress(),
    /** The most recent study day with at least one real review, possibly today. */
    val lastStudyDay: LocalDate?,
    val recovery: RecoveryState?,
    val holiday: HolidayStatus = HolidayStatus.None,
    val pace: PaceEstimate = PaceEstimate.DEFAULT,
    val size: SessionSize = SessionSize.SUGGESTED,
    /** Average number of cards FSRS will make due per day over the next week, from the forecast. */
    val forecastDuePerDay: Double = 0.0,
    /** True on the first study day after a holiday ended (see HolidayRules.justEnded). */
    val cameBackFromHoliday: Boolean = false,
)
