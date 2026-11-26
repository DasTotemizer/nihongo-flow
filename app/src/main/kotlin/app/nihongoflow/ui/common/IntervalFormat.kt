package app.nihongoflow.ui.common

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Short labels for the answer buttons, such as "1m", "10m", "3d", "2mo" or "1.5y". */
object IntervalFormat {
    fun short(d: Duration): String = when {
        d < 1.minutes -> "<1m"
        d < 1.hours -> "${d.inWholeMinutes}m"
        d < 1.days -> "${d.inWholeHours}h"
        d < 30.days -> "${(d.inWholeHours / 24.0).roundToInt().coerceAtLeast(1)}d"
        d < 365.days -> "${(d.inWholeDays / 30.4).roundToInt().coerceAtLeast(1)}mo"
        else -> {
            val years = d.inWholeDays / 365.25
            if (years < 10) "${(years * 10).roundToInt() / 10.0}y".replace(".0y", "y") else "${years.roundToInt()}y"
        }
    }
}
