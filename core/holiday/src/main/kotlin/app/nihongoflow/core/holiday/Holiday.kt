package app.nihongoflow.core.holiday

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** The three kinds of break the learner can take. */
enum class HolidayMode {
    /** No new cards, no reminders, no targets, no expectation to study. */
    FULL,

    /** Optional short, relaxed sessions with no targets and no new cards. */
    CASUAL,

    /** An open-ended pause for unexpected breaks, until the learner chooses to return. */
    INDEFINITE_PAUSE,
}

/**
 * One holiday, inclusive of both dates. [endsOn] null means it lasts until the learner ends
 * it. A holiday only changes what the app asks of the learner; it never changes memory
 * state or review history, and real elapsed time still counts for FSRS.
 */
data class HolidayPeriod(
    val id: String,
    val mode: HolidayMode,
    val startsOn: LocalDate,
    val endsOn: LocalDate?,
) {
    init {
        require(endsOn == null || endsOn >= startsOn) { "A holiday cannot end before it starts" }
        require(mode != HolidayMode.INDEFINITE_PAUSE || endsOn == null) {
            "An indefinite pause has no end date"
        }
    }

    fun covers(day: LocalDate): Boolean = day >= startsOn && (endsOn == null || day <= endsOn)

    private fun overlaps(other: HolidayPeriod): Boolean {
        val thisEndsBeforeOther = endsOn != null && endsOn < other.startsOn
        val otherEndsBeforeThis = other.endsOn != null && other.endsOn < startsOn
        return !thisEndsBeforeOther && !otherEndsBeforeThis
    }

    internal fun overlapsAny(others: Collection<HolidayPeriod>) = others.any { it.id != id && overlaps(it) }
}

/** What a holiday means for today's study. */
data class HolidayEffects(
    val suppressReminders: Boolean,
    val pauseNewCards: Boolean,
    val showDailyTarget: Boolean,
    /** When non-null, any session offered is capped at this many minutes. */
    val sessionCapMinutes: Int?,
    /** Whether the app should suggest a session at all, rather than just allow one. */
    val suggestSession: Boolean,
) {
    companion object {
        val NONE = HolidayEffects(
            suppressReminders = false,
            pauseNewCards = false,
            showDailyTarget = true,
            sessionCapMinutes = null,
            suggestSession = true,
        )
    }
}

sealed interface HolidayStatus {
    data object None : HolidayStatus
    data class Active(val period: HolidayPeriod) : HolidayStatus
    data class Upcoming(val period: HolidayPeriod) : HolidayStatus

    val effects: HolidayEffects
        get() = when (this) {
            is Active -> HolidayRules.effectsOf(period.mode)
            else -> HolidayEffects.NONE
        }
}

/** Pure rules for scheduling, ending and interpreting holidays. Persistence lives elsewhere. */
object HolidayRules {

    /** Default and allowed length of a casual-holiday session. */
    const val CASUAL_SESSION_MINUTES = 5
    val CASUAL_SESSION_RANGE = 2..5

    fun effectsOf(mode: HolidayMode, casualMinutes: Int = CASUAL_SESSION_MINUTES): HolidayEffects = when (mode) {
        HolidayMode.FULL, HolidayMode.INDEFINITE_PAUSE -> HolidayEffects(
            suppressReminders = true,
            pauseNewCards = true,
            showDailyTarget = false,
            // A learner on holiday may still open the app; offer a casual-sized session.
            sessionCapMinutes = casualMinutes.coerceIn(CASUAL_SESSION_RANGE),
            suggestSession = false,
        )
        HolidayMode.CASUAL -> HolidayEffects(
            suppressReminders = true,
            pauseNewCards = true,
            showDailyTarget = false,
            sessionCapMinutes = casualMinutes.coerceIn(CASUAL_SESSION_RANGE),
            suggestSession = true,
        )
    }

    fun statusOn(day: LocalDate, periods: Collection<HolidayPeriod>): HolidayStatus {
        periods.firstOrNull { it.covers(day) }?.let { return HolidayStatus.Active(it) }
        return periods.filter { it.startsOn > day }.minByOrNull { it.startsOn }
            ?.let { HolidayStatus.Upcoming(it) }
            ?: HolidayStatus.None
    }

    /** Adds or replaces [period]. Fails if it would overlap another holiday. */
    fun schedule(existing: List<HolidayPeriod>, period: HolidayPeriod): Result<List<HolidayPeriod>> {
        if (period.overlapsAny(existing)) {
            return Result.failure(IllegalArgumentException("This holiday overlaps another one"))
        }
        return Result.success(existing.filterNot { it.id == period.id } + period)
    }

    /**
     * Ends the holiday covering [today] so that today is a normal day again. A holiday that
     * starts today is removed entirely, because none of it has happened yet.
     */
    fun endNow(existing: List<HolidayPeriod>, today: LocalDate): List<HolidayPeriod> =
        existing.mapNotNull { p ->
            when {
                !p.covers(today) -> p
                p.startsOn >= today -> null
                else -> {
                    val yesterday = today.minus(1, DateTimeUnit.DAY)
                    // An indefinite pause becomes a dated full holiday once it ends.
                    val mode = if (p.mode == HolidayMode.INDEFINITE_PAUSE) HolidayMode.FULL else p.mode
                    p.copy(mode = mode, endsOn = yesterday)
                }
            }
        }

    /** Removes a holiday that has not started yet. Past holidays are kept as history. */
    fun cancelUpcoming(existing: List<HolidayPeriod>, id: String, today: LocalDate): List<HolidayPeriod> =
        existing.filterNot { it.id == id && it.startsOn > today }

    /**
     * The most recent holiday that ended before [today], if [today] is the first day after
     * it. Used to show the return-from-holiday welcome.
     */
    fun justEnded(periods: Collection<HolidayPeriod>, today: LocalDate, lastStudyDay: LocalDate?): HolidayPeriod? =
        periods
            .filter { it.endsOn != null && it.endsOn < today }
            .filter { lastStudyDay == null || lastStudyDay <= it.endsOn!! }
            .maxByOrNull { it.endsOn!! }
}
