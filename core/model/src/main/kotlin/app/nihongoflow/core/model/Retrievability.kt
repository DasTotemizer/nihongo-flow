package app.nihongoflow.core.model

import kotlinx.datetime.Instant

/**
 * Read-only access to the memory model's recall probability. The workload manager depends
 * on this interface instead of on FSRS itself, which keeps the two systems separate.
 */
fun interface RetrievabilityEstimator {
    /** Probability (0..1) of recalling the card at [at], or null for a card never reviewed. */
    fun retrievability(state: MemoryState, at: Instant): Double?
}
