package app.nihongoflow.ui.copy

import app.nihongoflow.core.workload.Greeting
import app.nihongoflow.core.workload.NewCardPolicy
import app.nihongoflow.core.workload.SessionPlan

/**
 * Every sentence the home screen can say about the learner's workload. Kept in one place
 * so a test can check that none of it is guilt-based (see GreetingCopyTest).
 */
object GreetingCopy {

    fun headline(greeting: Greeting): String = when (greeting) {
        Greeting.WELCOME -> "Welcome back!"
        Greeting.WELCOME_BACK_EASE_IN -> "Welcome back. Let's ease into it."
        Greeting.WELCOME_BACK_FROM_HOLIDAY -> "Welcome back from your break."
        Greeting.RECOVERY_CONTINUES -> "Nice to see you again."
        Greeting.HOLIDAY_ACTIVE -> "Holiday Mode is active."
        Greeting.CASUAL_HOLIDAY -> "Casual holiday: just a little, if you like."
        Greeting.REST_DAY -> "Today is a rest day."
        Greeting.ALL_DONE_TODAY -> "That's today's session done. Well played."
        Greeting.NOTHING_DUE -> "Let's get you started."
    }

    fun detail(plan: SessionPlan): String = detail(plan.greeting, plan.recoveryProgress != null)

    fun detail(greeting: Greeting, spreadingReviews: Boolean): String = when (greeting) {
        Greeting.WELCOME_BACK_EASE_IN, Greeting.WELCOME_BACK_FROM_HOLIDAY ->
            if (spreadingReviews) {
                "We've prepared a manageable session and will spread the remaining reviews over time."
            } else {
                "Your suggested session is ready."
            }
        Greeting.RECOVERY_CONTINUES -> "Here's another comfortable session. You're getting back into the flow."
        Greeting.HOLIDAY_ACTIVE -> "Your learning will be here when you return."
        Greeting.CASUAL_HOLIDAY -> "No targets and no pressure. A few minutes is plenty."
        Greeting.REST_DAY -> "Nothing is planned today, but you're welcome to practise."
        Greeting.ALL_DONE_TODAY -> "You can stop here, or do a few more if you're enjoying it."
        Greeting.NOTHING_DUE -> "Install Kaishi 1.5k from My Decks, or create your own cards."
        Greeting.WELCOME -> "Your suggested session is ready."
    }

    fun sessionSummary(plan: SessionPlan): String {
        val cards = plan.queue.size
        if (cards == 0) return ""
        val noun = if (cards == 1) "card" else "cards"
        return "$cards $noun · Approximately ${plan.estimatedMinutes} minute${if (plan.estimatedMinutes == 1) "" else "s"}"
    }

    fun newCardNote(policy: NewCardPolicy): String? = when (policy) {
        NewCardPolicy.PAUSED_FOR_RECOVERY -> "New cards are resting for a few days while you settle back in."
        NewCardPolicy.PAUSED_FOR_HOLIDAY -> "New cards are paused during your holiday."
        NewCardPolicy.PAUSED_FOR_WORKLOAD -> "No new cards today, so your upcoming sessions stay comfortable."
        NewCardPolicy.REDUCED -> "A few new cards today, at an easy pace."
        NewCardPolicy.FULL, NewCardPolicy.DAILY_LIMIT_REACHED, NewCardPolicy.NONE_AVAILABLE -> null
    }

    /** Every fixed string, for checks. */
    val allFixedStrings: List<String>
        get() = Greeting.entries.map { headline(it) } +
            Greeting.entries.flatMap { listOf(detail(it, true), detail(it, false)) } +
            NewCardPolicy.entries.mapNotNull { newCardNote(it) }
}
