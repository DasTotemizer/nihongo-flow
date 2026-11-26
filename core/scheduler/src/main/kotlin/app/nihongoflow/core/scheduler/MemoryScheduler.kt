package app.nihongoflow.core.scheduler

import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.RetrievabilityEstimator
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The memory scheduler decides when a card is theoretically due, from its real review
 * history and the real elapsed time. It knows nothing about sessions, backlogs or holidays.
 *
 * An iOS client can provide its own implementation (for example over swift-fsrs or
 * fsrs-rs) behind the same interface.
 */
interface MemoryScheduler : RetrievabilityEstimator {

    /**
     * Applies one real answer given at [reviewedAt].
     *
     * @throws IllegalArgumentException if [reviewedAt] is before the card's last review,
     *   because time cannot run backwards in a review history.
     */
    fun review(state: MemoryState, rating: Rating, reviewedAt: Instant): MemoryState

    /**
     * What the due time would be for each possible answer, for showing on the answer
     * buttons. Previews do not apply interval fuzz, so the real due time may differ by a
     * small amount for longer intervals.
     */
    fun preview(state: MemoryState, at: Instant): Map<Rating, Instant>
}

/**
 * Scheduler configuration. Defaults follow the product spec: FSRS-6 default parameters and
 * 90% desired retention.
 *
 * @property parameters 21 FSRS-6 weights, or null for the library defaults. Only replace
 *   these with weights produced by a real optimiser run on real review history.
 */
data class SchedulerConfig(
    val desiredRetention: Double = 0.9,
    val parameters: List<Double>? = null,
    val learningSteps: List<Duration> = listOf(1.minutes, 10.minutes),
    val relearningSteps: List<Duration> = listOf(10.minutes),
    val maximumIntervalDays: Int = 36_500,
    val enableFuzzing: Boolean = true,
) {
    init {
        require(desiredRetention in MIN_RETENTION..MAX_RETENTION) {
            "desiredRetention must be between $MIN_RETENTION and $MAX_RETENTION"
        }
        require(maximumIntervalDays >= 1) { "maximumIntervalDays must be at least 1" }
        parameters?.let { ParameterSafeguards.requireValid(it) }
    }

    companion object {
        const val MIN_RETENTION = 0.70
        const val MAX_RETENTION = 0.97
    }
}
