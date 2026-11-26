package app.nihongoflow.core.workload

import app.nihongoflow.core.model.CardId
import kotlinx.datetime.LocalDate

enum class PlanMode {
    /** An ordinary day. */
    NORMAL,

    /** Coming back after a break, or more is due than one comfortable session holds. */
    RECOVERY,

    /** Full holiday or indefinite pause: nothing is expected, a short session is optional. */
    HOLIDAY,

    /** Casual holiday: a short relaxed session is suggested, with no targets. */
    CASUAL_HOLIDAY,

    /** Not one of the learner's study days. Studying is allowed but not suggested. */
    REST_DAY,

    /** Today's suggested reviews are finished. */
    DONE_FOR_TODAY,

    /** Nothing is due and there are no new cards to introduce. */
    NOTHING_DUE,
}

enum class CardKind { LEARNING, REVIEW, NEW }

/** Why a card is in the session. Shown to the learner on request. */
enum class PlanReason {
    /** Mid-way through its short learning or relearning steps. */
    LEARNING_STEP,

    /** Left out of earlier sessions on several days, so it now has a guaranteed place. */
    WAITED_LONGEST,

    /** Its memory is fading fastest, so reviewing it now helps most. */
    FORGETTING_RISK,

    /** Due today and fits in the session. */
    DUE_TODAY,

    NEW_CARD,
}

enum class DeferralReason {
    /** Did not fit in today's comfortable session; stays eligible for the next ones. */
    SESSION_LIMIT,
    HOLIDAY,
    REST_DAY,
}

enum class NewCardPolicy {
    FULL,

    /** Fewer new cards while easing back in, or because upcoming reviews are already busy. */
    REDUCED,

    PAUSED_FOR_RECOVERY,
    PAUSED_FOR_HOLIDAY,
    PAUSED_FOR_WORKLOAD,

    /** Today's new cards have already been introduced. */
    DAILY_LIMIT_REACHED,

    /** The learner chose 0 new cards per day, or there are no new cards left. */
    NONE_AVAILABLE,
}

/** What the home screen says first. The app maps each to friendly copy. */
enum class Greeting {
    WELCOME,
    WELCOME_BACK_EASE_IN,
    WELCOME_BACK_FROM_HOLIDAY,
    RECOVERY_CONTINUES,
    HOLIDAY_ACTIVE,
    CASUAL_HOLIDAY,
    REST_DAY,
    ALL_DONE_TODAY,
    NOTHING_DUE,
}

data class PlannedCard(val cardId: CardId, val kind: CardKind, val reason: PlanReason)

data class DeferredCard(val cardId: CardId, val reason: DeferralReason)

/**
 * The true numbers behind a plan, kept apart so statistics never mix them up. The home
 * screen does not lead with these; the Progress screen can show them on request.
 */
data class DueAccounting(
    /** Cards FSRS considers due by the end of today (learning steps due soon included). */
    val dueByFsrs: Int,
    /** Cards placed in this session. */
    val plannedNow: Int,
    /** Due cards left out of this session by the workload manager. */
    val deferredByWorkload: Int,
    /** Real reviews already done today. */
    val reviewedToday: Int,
)

/** Gentle progress indicator for a return. */
data class RecoveryProgress(
    /** 0..1, how much of the backlog present at the start of the return has been worked through. */
    val fraction: Double,
    /** Due cards still waiting after this session (shown only on request). */
    val remainingAfterSession: Int,
    /** Estimated study days until back to normal, or null when it cannot be estimated. */
    val estimatedStudyDays: Int?,
    /**
     * True when the daily session is too small to shrink the backlog at all. The app then
     * suggests options (a slightly longer session, or a lower desired retention) instead of
     * letting hidden debt grow silently.
     */
    val needsLargerSessions: Boolean,
)

data class SessionPlan(
    val day: LocalDate,
    val mode: PlanMode,
    val greeting: Greeting,
    /** Cards in the order they should be shown. */
    val queue: List<PlannedCard>,
    /** Whether the app suggests this session, or merely offers it. */
    val suggested: Boolean,
    val showDailyTarget: Boolean,
    val estimatedMinutes: Int,
    val newCardPolicy: NewCardPolicy,
    val deferred: List<DeferredCard>,
    val accounting: DueAccounting,
    val recoveryProgress: RecoveryProgress?,
    /** State to persist for the next plan. Null when no recovery is in progress. */
    val nextRecoveryState: RecoveryState?,
) {
    val reviewCount: Int get() = queue.count { it.kind != CardKind.NEW }
    val newCount: Int get() = queue.count { it.kind == CardKind.NEW }
}
