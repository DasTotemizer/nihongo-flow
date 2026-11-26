package app.nihongoflow.core.study

import app.nihongoflow.core.scheduler.SchedulerConfig
import app.nihongoflow.core.workload.RecoveryPacing
import app.nihongoflow.core.workload.WorkloadSettings
import kotlinx.datetime.DayOfWeek

/** Everything the learner can configure about studying. Defaults follow the spec. */
data class StudySettings(
    val desiredRetention: Double = 0.9,
    val targetSessionMinutes: Int = 10,
    val maxSessionMinutes: Int = 15,
    val maxReviewsPerSession: Int = 60,
    val newCardsPerDay: Int = 5,
    val recoveryPacing: RecoveryPacing = RecoveryPacing.GENTLE,
    val preferredStudyDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val casualSessionMinutes: Int = 5,
    val dayStartHour: Int = 4,
    val enabledDirections: Set<ReviewDirection> = ReviewDirection.DEFAULT_ENABLED,
    /**
     * Answer with two buttons, Forgot and Remembered (FSRS Again and Good), instead of four.
     * This is FSRS's supported pass/fail use; nothing is estimated or faked.
     */
    val twoButtonReview: Boolean = true,
) {
    fun workload() = WorkloadSettings(
        targetSessionMinutes = targetSessionMinutes,
        maxSessionMinutes = maxOf(maxSessionMinutes, targetSessionMinutes),
        maxReviewsPerSession = maxReviewsPerSession,
        newCardsPerDay = newCardsPerDay,
        recoveryPacing = recoveryPacing,
        preferredStudyDays = preferredStudyDays.ifEmpty { DayOfWeek.entries.toSet() },
        casualSessionMinutes = casualSessionMinutes,
    )

    fun scheduler() = SchedulerConfig(desiredRetention = desiredRetention)
}
