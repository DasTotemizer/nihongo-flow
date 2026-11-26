package app.nihongoflow.core.workload

import app.nihongoflow.core.model.CardId
import kotlinx.datetime.LocalDate

/**
 * Keeps the presentation bookkeeping that makes deferral fair. Only sessions the learner
 * actually starts count: a plan that is merely displayed changes nothing, and holiday or
 * rest-day deferrals do not count against the fairness limit.
 */
object DeferralLedger {

    /** Call when the learner starts a session from [plan]. Returns the updated states. */
    fun recordSessionStart(
        states: Map<CardId, DeferralState>,
        plan: SessionPlan,
    ): Map<CardId, DeferralState> {
        val day = plan.day
        val updated = states.toMutableMap()
        for (d in plan.deferred) {
            if (d.reason != DeferralReason.SESSION_LIMIT) continue
            updated[d.cardId] = bump(updated[d.cardId], day)
        }
        // Cards placed in the session keep their state until they are really reviewed, so an
        // abandoned session does not reset how long they have waited.
        return updated
    }

    /** Call after a real review of [cardId]. */
    fun recordReviewed(states: Map<CardId, DeferralState>, cardId: CardId): Map<CardId, DeferralState> =
        states - cardId

    private fun bump(existing: DeferralState?, day: LocalDate): DeferralState = when {
        existing == null -> DeferralState(firstDeferredOn = day, lastDeferredOn = day, studyDaysDeferred = 1)
        existing.lastDeferredOn >= day -> existing // once per study day
        else -> existing.copy(lastDeferredOn = day, studyDaysDeferred = existing.studyDaysDeferred + 1)
    }
}
