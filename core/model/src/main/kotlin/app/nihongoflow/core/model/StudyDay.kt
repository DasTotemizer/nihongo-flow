package app.nihongoflow.core.model

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * A learner's "study day" starts at a configurable hour (4 AM by default, like Anki), so a
 * session just after midnight still counts as the previous evening.
 */
class StudyDayClock(
    val timeZone: TimeZone,
    val dayStartHour: Int = 4,
) {
    init {
        require(dayStartHour in 0..23) { "dayStartHour must be 0..23" }
    }

    /** The study day that [instant] belongs to. */
    fun studyDayOf(instant: Instant): LocalDate {
        val local = instant.toLocalDateTime(timeZone)
        return if (local.hour < dayStartHour) local.date.minus(1, DateTimeUnit.DAY) else local.date
    }

    /** The instant the given study day begins. */
    fun startOf(day: LocalDate): Instant =
        if (dayStartHour == 0) day.atStartOfDayIn(timeZone)
        else LocalDateTime(day, LocalTime(dayStartHour, 0)).toInstant(timeZone)

    /** The instant the given study day ends (the start of the next one). */
    fun endOf(day: LocalDate): Instant = startOf(day.plus(1, DateTimeUnit.DAY))
}
