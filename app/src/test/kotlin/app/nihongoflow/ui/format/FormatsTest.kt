package app.nihongoflow.ui.format

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FormatsTest {
    private val t = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun intervals() {
        assertEquals("<1m", Formats.interval(t, t + 20.seconds))
        assertEquals("10m", Formats.interval(t, t + 10.minutes))
        assertEquals("5h", Formats.interval(t, t + 5.hours))
        assertEquals("3d", Formats.interval(t, t + 3.days))
        assertEquals("2mo", Formats.interval(t, t + 61.days))
        assertEquals("1.5y", Formats.interval(t, t + 548.days))
        assertEquals("90%", Formats.percent(0.9))
    }
}
