package app.nihongoflow.core.study

import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.model.StudyDayClock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

data class DayCount(val day: LocalDate, val count: Int)

/**
 * The basic numbers on the Progress screen. No streaks and nothing that counts missed
 * days: only what the learner has done and what is coming up.
 */
data class StudyStats(
    val totalCards: Int,
    val newCards: Int,
    val learningCards: Int,
    /** Reviewed cards with stability under 21 days. */
    val youngCards: Int,
    /** Reviewed cards with stability of 21 days or more. */
    val matureCards: Int,
    val reviewsToday: Int,
    val newCardsToday: Int,
    val minutesToday: Int,
    /** Answers per study day for the last 7 study days, oldest first. */
    val last7Days: List<DayCount>,
    val studyDaysLast30: Int,
    /** Share of review-phase answers in the last 30 days that were not "Again". Null below 10 answers. */
    val retentionLast30: Double?,
    val dueToday: Int,
    /** Cards FSRS will make due on each of the next 7 study days. */
    val forecast: List<DayCount>,
) {
    companion object {
        const val MATURE_STABILITY_DAYS = 21.0
        private const val MIN_ANSWERS_FOR_RETENTION = 10

        fun compute(
            now: Instant,
            clock: StudyDayClock,
            states: List<MemoryState>,
            recentReviews: List<ReviewRecord>,
            forecastDueTimes: List<Instant>,
            dueToday: Int,
        ): StudyStats {
            val today = clock.studyDayOf(now)
            val byDay = recentReviews.groupBy { clock.studyDayOf(it.reviewedAt) }
            val todays = byDay[today].orEmpty()
            val reviewed = states.filter { !it.isNew && it.phase == MemoryPhase.REVIEW }
            val reviewPhaseAnswers = recentReviews.filter { it.stateBefore.phase == MemoryPhase.REVIEW }
            val forecastByDay = forecastDueTimes.groupBy { clock.studyDayOf(it) }
            return StudyStats(
                totalCards = states.size,
                newCards = states.count { it.isNew },
                learningCards = states.count { it.phase == MemoryPhase.LEARNING || it.phase == MemoryPhase.RELEARNING },
                youngCards = reviewed.count { (it.stability ?: 0.0) < MATURE_STABILITY_DAYS },
                matureCards = reviewed.count { (it.stability ?: 0.0) >= MATURE_STABILITY_DAYS },
                reviewsToday = todays.size,
                newCardsToday = todays.count { it.stateBefore.isNew },
                minutesToday = ((todays.sumOf { it.durationMillis ?: 0L } + 30_000) / 60_000).toInt(),
                last7Days = (6 downTo 0).map { back ->
                    val d = today.plus(-back, DateTimeUnit.DAY)
                    DayCount(d, byDay[d]?.size ?: 0)
                },
                studyDaysLast30 = byDay.keys.count { it > today.plus(-30, DateTimeUnit.DAY) },
                retentionLast30 = reviewPhaseAnswers.takeIf { it.size >= MIN_ANSWERS_FOR_RETENTION }
                    ?.let { list -> list.count { it.rating != Rating.AGAIN }.toDouble() / list.size },
                dueToday = dueToday,
                forecast = (1..7).map { ahead ->
                    val d = today.plus(ahead, DateTimeUnit.DAY)
                    DayCount(d, forecastByDay[d]?.size ?: 0)
                },
            )
        }
    }
}
