package app.nihongoflow.core.study

import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.workload.CardKind
import app.nihongoflow.core.workload.PlanReason
import app.nihongoflow.core.workload.PlannedCard
import app.nihongoflow.core.workload.SessionPlan
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The order of cards in one sitting. Starts from the workload manager's queue; a card that
 * goes into a short learning step (for example after "Again") comes back later in the same
 * sitting once its step is due, or is learned slightly ahead when nothing else is left.
 *
 * This class only orders cards. Answers are applied by [StudyService.answer].
 */
class ReviewSession(
    val plan: SessionPlan,
    private val learnAhead: Duration = 20.minutes,
) {
    private val planned = ArrayDeque(plan.queue)
    private val waiting = mutableListOf<Pair<CardId, Instant>>()

    /** Answers given in this sitting, including repeats of the same card. */
    var answered: Int = 0
        private set

    private val seen = mutableSetOf<CardId>()

    /** Distinct cards answered at least once. */
    val cardsSeen: Int get() = seen.size

    val plannedTotal: Int = plan.queue.size

    /** Cards still to show from the original plan. */
    val plannedRemaining: Int get() = planned.size

    /** Cards in a short learning step that will come back in this sitting. */
    val learningRemaining: Int get() = waiting.size

    /** The card to show now, or null when the sitting is finished. */
    fun current(now: Instant): PlannedCard? {
        waiting.minByOrNull { it.second }?.takeIf { it.second <= now }?.let { (id, _) ->
            return PlannedCard(id, CardKind.LEARNING, PlanReason.LEARNING_STEP)
        }
        planned.firstOrNull()?.let { return it }
        // Nothing else to do: learn the next step a little early rather than make the
        // learner wait, as long as it is due soon.
        val next = waiting.minByOrNull { it.second } ?: return null
        return if (next.second <= now + learnAhead) {
            PlannedCard(next.first, CardKind.LEARNING, PlanReason.LEARNING_STEP)
        } else null
    }

    /** Call after [cardId] was answered and FSRS produced [after]. */
    fun onAnswered(cardId: CardId, after: MemoryState, now: Instant) {
        answered++
        seen += cardId
        planned.removeAll { it.cardId == cardId }
        waiting.removeAll { it.first == cardId }
        val inSteps = after.phase == MemoryPhase.LEARNING || after.phase == MemoryPhase.RELEARNING
        if (inSteps && after.due <= now + learnAhead) waiting += cardId to after.due
    }

    /** Skips [cardId] for this sitting without answering it. Memory state is untouched. */
    fun skip(cardId: CardId) {
        planned.removeAll { it.cardId == cardId }
        waiting.removeAll { it.first == cardId }
    }

    fun isFinished(now: Instant): Boolean = current(now) == null
}
