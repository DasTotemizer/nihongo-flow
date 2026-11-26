package app.nihongoflow.ui.common

import kotlinx.datetime.LocalDate

/** Friendly dates such as "Wed 2 Dec". Kept simple and locale-neutral for now. */
object DateFormat {
    fun short(date: LocalDate): String {
        val weekday = date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        val month = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        return "$weekday ${date.dayOfMonth} $month"
    }
}
