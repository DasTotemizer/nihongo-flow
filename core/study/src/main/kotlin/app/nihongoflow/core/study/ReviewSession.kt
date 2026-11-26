package app.nihongoflow.core.study

import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.workload.PlannedCard
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The cards of one session, in order. A card answered Again (or still in its short learning
 * steps) comes back later in the same session once its step is due, as in Anki.
 */
class ReviewSession(
    planned: List<PlannedCard>,
    private val learnAhead: Duration = 20.minutes,
) {
    private val queue = ArrayDeque(planned.map { it.cardId })
    private val waiting = mutableListOf<Pair<CardId, Instant>>()

    var answered: Int = 0
        private set

    val plannedCount: Int = planned.size

    /** The next card to show at [now], or null when the session is finished. */
    fun next(now: Instant): CardId? {
        val ready = waiting.filter { it.second <= now }.minByOrNull { it.second }
        if (ready != null) {
            waiting.remove(ready)
            return ready.first
        }
        queue.removeFirstOrNull()?.let { return it }
        // Nothing else left: show the soonest waiting learning step rather than end early.
        val soonest = waiting.minByOrNull { it.second } ?: return null
        waiting.remove(soonest)
        return soonest.first
    }

    /** Call after each answer with the card's new memory state. */
    fun onAnswered(cardId: CardId, newState: MemoryState, now: Instant) {
        answered++
        val shortStep = newState.phase == MemoryPhase.LEARNING || newState.phase == MemoryPhase.RELEARNING
        if (shortStep && newState.due <= now + learnAhead) waiting += cardId to newState.due
    }

    val remaining: Int get() = queue.size + waiting.size
}
