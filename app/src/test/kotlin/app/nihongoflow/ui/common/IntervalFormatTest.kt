package app.nihongoflow.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class IntervalFormatTest {
    @Test
    fun `labels are short and readable`() {
        assertEquals("<1m", IntervalFormat.short(30.seconds))
        assertEquals("1m", IntervalFormat.short(1.minutes))
        assertEquals("10m", IntervalFormat.short(10.minutes))
        assertEquals("5h", IntervalFormat.short(5.hours))
        assertEquals("1d", IntervalFormat.short(1.days))
        assertEquals("3d", IntervalFormat.short(3.days))
        assertEquals("2mo", IntervalFormat.short(61.days))
        assertEquals("1y", IntervalFormat.short(366.days))
        assertEquals("1.5y", IntervalFormat.short(548.days))
        assertEquals("25y", IntervalFormat.short((25 * 365.25).days))
    }
}
