package app.nihongoflow.core.anki

import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.model.ReviewSource
import app.nihongoflow.core.scheduler.MemoryScheduler
import kotlinx.datetime.Instant

/**
 * Rebuilds a card's FSRS memory from its real Anki review log. Every answer is run through
 * the same scheduler the app uses, in the order it happened, so the resulting state is
 * what FSRS would have produced had the reviews been done here. Nothing is estimated from
 * Anki's own intervals or ease, and no review is invented.
 */
object HistoryReplay {

    data class Result(
        /** The memory after the last answer, or null if the card ends up new (never answered, or reset). */
        val state: MemoryState?,
        val records: List<ReviewRecord>,
    )

    fun replay(cardId: CardId, reviews: List<AnkiReview>, scheduler: MemoryScheduler): Result {
        var state: MemoryState? = null
        val records = mutableListOf<ReviewRecord>()
        for (r in reviews.sortedBy { it.id }) {
            val at = Instant.fromEpochMilliseconds(r.id)
            if (r.ease == 0) {
                // Manual entries. "Forget" (type 4, interval 0) resets the card in Anki, so it
                // starts again as new here too. Other manual changes are not answers: skipped.
                if (r.type == MANUAL && r.interval == 0) state = null
                continue
            }
            val rating = when (r.ease) {
                1 -> Rating.AGAIN
                2 -> Rating.HARD
                3 -> Rating.GOOD
                4 -> Rating.EASY
                else -> continue
            }
            if (r.type == RESCHEDULED) continue
            val before = state ?: MemoryState.new(at)
            val last = before.lastReview
            if (last != null && at < last) continue
            val after = scheduler.review(before, rating, at)
            records += ReviewRecord(
                cardId = cardId,
                rating = rating,
                reviewedAt = at,
                durationMillis = r.takenMillis.takeIf { it > 0 },
                stateBefore = before,
                stateAfter = after,
                source = ReviewSource.ANKI_IMPORT,
            )
            state = after
        }
        return Result(state, records)
    }

    private const val MANUAL = 4
    private const val RESCHEDULED = 5
}
