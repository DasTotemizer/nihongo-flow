package app.nihongoflow.ui.format

import kotlinx.datetime.Instant
import kotlin.math.roundToInt

object Formats {

    /** Short, friendly interval for answer buttons: "1m", "10m", "4h", "3d", "2mo", "1.5y". */
    fun interval(from: Instant, to: Instant): String {
        val minutes = (to - from).inWholeMinutes.coerceAtLeast(0)
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            minutes < 60 * 24 -> "${(minutes / 60.0).roundToInt()}h"
            minutes < 60 * 24 * 30 -> "${(minutes / (60.0 * 24)).roundToInt()}d"
            minutes < 60 * 24 * 365 -> "${(minutes / (60.0 * 24 * 30)).roundToInt()}mo"
            else -> {
                val years = minutes / (60.0 * 24 * 365)
                if (years < 10) "${(years * 10).roundToInt() / 10.0}y" else "${years.roundToInt()}y"
            }
        }
    }

    fun percent(value: Double): String = "${(value * 100).roundToInt()}%"
}
