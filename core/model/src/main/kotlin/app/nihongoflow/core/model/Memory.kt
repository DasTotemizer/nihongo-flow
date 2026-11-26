package app.nihongoflow.core.model

import kotlinx.datetime.Instant

/** Stable identifier of a reviewable card (one card per note template / cloze number). */
typealias CardId = Long

/** The four standard review answers. Every answer is processed by FSRS. */
enum class Rating { AGAIN, HARD, GOOD, EASY }

/**
 * Where a card is in its memory life cycle, as tracked by the memory scheduler.
 *
 * [NEW] means the learner has never reviewed the card. New cards are not "due" in the
 * FSRS sense; the workload manager decides when to introduce them.
 */
enum class MemoryPhase { NEW, LEARNING, REVIEW, RELEARNING }

/**
 * The FSRS memory state of one card. Owned exclusively by the memory scheduler.
 *
 * The workload manager reads this but never writes it: deferring a card in a session
 * must not change its stability, difficulty, due date or last review time.
 *
 * @property step index into the learning or relearning steps, null when in [MemoryPhase.REVIEW].
 * @property stability FSRS stability in days, null until the first review.
 * @property difficulty FSRS difficulty (1..10), null until the first review.
 * @property due when FSRS says the card should next be reviewed.
 * @property lastReview the time of the most recent real review, null if never reviewed.
 */
data class MemoryState(
    val phase: MemoryPhase,
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val due: Instant,
    val lastReview: Instant?,
) {
    val isNew: Boolean get() = phase == MemoryPhase.NEW

    companion object {
        fun new(createdAt: Instant) = MemoryState(
            phase = MemoryPhase.NEW,
            step = null,
            stability = null,
            difficulty = null,
            due = createdAt,
            lastReview = null,
        )
    }
}

/** Where a review record came from. History from imports is kept and labelled, never invented. */
enum class ReviewSource { NIHONGO_FLOW, ANKI_IMPORT }

/**
 * One real review, appended to the history and never edited afterwards.
 *
 * @property stateBefore memory state immediately before the answer, so the history can be
 *   replayed or re-optimised later.
 * @property stateAfter memory state the scheduler produced for this answer.
 * @property durationMillis how long the learner took to answer, when measured.
 */
data class ReviewRecord(
    val cardId: CardId,
    val rating: Rating,
    val reviewedAt: Instant,
    val durationMillis: Long?,
    val stateBefore: MemoryState,
    val stateAfter: MemoryState,
    val source: ReviewSource = ReviewSource.NIHONGO_FLOW,
)
