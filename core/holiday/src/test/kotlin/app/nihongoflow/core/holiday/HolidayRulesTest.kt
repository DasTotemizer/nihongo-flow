package app.nihongoflow.core.holiday

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HolidayRulesTest {

    private val d1 = LocalDate(2026, 8, 1)
    private val d7 = LocalDate(2026, 8, 7)
    private val d10 = LocalDate(2026, 8, 10)
    private val d14 = LocalDate(2026, 8, 14)

    @Test
    fun `status follows the calendar, including both end dates`() {
        val trip = HolidayPeriod("trip", HolidayMode.FULL, d1, d7)
        assertIs<HolidayStatus.Upcoming>(HolidayRules.statusOn(LocalDate(2026, 7, 31), listOf(trip)))
        assertIs<HolidayStatus.Active>(HolidayRules.statusOn(d1, listOf(trip)))
        assertIs<HolidayStatus.Active>(HolidayRules.statusOn(d7, listOf(trip)))
        // Automatically resumes normal operation the day after the holiday ends.
        assertEquals(HolidayStatus.None, HolidayRules.statusOn(LocalDate(2026, 8, 8), listOf(trip)))
    }

    @Test
    fun `full holiday removes every expectation`() {
        val e = HolidayRules.effectsOf(HolidayMode.FULL)
        assertTrue(e.suppressReminders)
        assertTrue(e.pauseNewCards)
        assertTrue(!e.showDailyTarget)
        assertTrue(!e.suggestSession)
    }

    @Test
    fun `casual holiday suggests a short optional session`() {
        val e = HolidayRules.effectsOf(HolidayMode.CASUAL, casualMinutes = 3)
        assertTrue(e.suggestSession)
        assertEquals(3, e.sessionCapMinutes)
        assertTrue(!e.showDailyTarget)
        assertTrue(e.pauseNewCards)
        assertEquals(5, HolidayRules.effectsOf(HolidayMode.CASUAL, casualMinutes = 30).sessionCapMinutes)
    }

    @Test
    fun `indefinite pause lasts until ended`() {
        val pause = HolidayPeriod("p", HolidayMode.INDEFINITE_PAUSE, d1, null)
        assertIs<HolidayStatus.Active>(HolidayRules.statusOn(LocalDate(2030, 1, 1), listOf(pause)))
        assertFailsWith<IllegalArgumentException> { HolidayPeriod("p", HolidayMode.INDEFINITE_PAUSE, d1, d7) }
    }

    @Test
    fun `ending a holiday makes today a normal day`() {
        val pause = HolidayPeriod("p", HolidayMode.INDEFINITE_PAUSE, d1, null)
        val ended = HolidayRules.endNow(listOf(pause), d10).single()
        assertEquals(LocalDate(2026, 8, 9), ended.endsOn)
        assertEquals(HolidayMode.FULL, ended.mode)
        assertEquals(HolidayStatus.None, HolidayRules.statusOn(d10, listOf(ended)))
        // The return is recognised.
        assertEquals(ended, HolidayRules.justEnded(listOf(ended), d10, lastStudyDay = LocalDate(2026, 7, 31)))
        // Once the learner has studied after it, it is no longer "just ended".
        assertNull(HolidayRules.justEnded(listOf(ended), LocalDate(2026, 8, 12), lastStudyDay = d10))
    }

    @Test
    fun `ending a holiday on its first day removes it`() {
        val trip = HolidayPeriod("trip", HolidayMode.FULL, d10, d14)
        assertTrue(HolidayRules.endNow(listOf(trip), d10).isEmpty())
    }

    @Test
    fun `holidays can be changed but not overlapped`() {
        val trip = HolidayPeriod("trip", HolidayMode.FULL, d1, d7)
        val list = HolidayRules.schedule(emptyList(), trip).getOrThrow()
        assertTrue(HolidayRules.schedule(list, HolidayPeriod("other", HolidayMode.CASUAL, d7, d10)).isFailure)
        // Changing the same holiday is allowed.
        val moved = HolidayRules.schedule(list, trip.copy(endsOn = d10)).getOrThrow()
        assertEquals(d10, moved.single().endsOn)
        // An open-ended holiday overlaps anything after it.
        val open = HolidayRules.schedule(emptyList(), HolidayPeriod("open", HolidayMode.FULL, d1, null)).getOrThrow()
        assertTrue(HolidayRules.schedule(open, HolidayPeriod("later", HolidayMode.FULL, d14, d14)).isFailure)
    }

    @Test
    fun `only upcoming holidays can be cancelled`() {
        val past = HolidayPeriod("past", HolidayMode.FULL, d1, d7)
        val next = HolidayPeriod("next", HolidayMode.FULL, d14, d14)
        val after = HolidayRules.cancelUpcoming(listOf(past, next), "next", d10)
        assertEquals(listOf(past), after)
        assertEquals(listOf(past), HolidayRules.cancelUpcoming(after, "past", d10))
    }

    @Test
    fun `end before start is rejected`() {
        assertFailsWith<IllegalArgumentException> { HolidayPeriod("x", HolidayMode.FULL, d7, d1) }
    }
}
