package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.scheduler.FsrsScheduler
import app.nihongoflow.core.scheduler.SchedulerConfig
import app.nihongoflow.core.workload.CardKind
import app.nihongoflow.core.workload.DeferralReason
import app.nihongoflow.core.workload.Greeting
import app.nihongoflow.core.workload.NewCardPolicy
import app.nihongoflow.core.workload.PlanMode
import app.nihongoflow.core.workload.WorkloadSettings
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class StudyServiceTest {
    private var time: Instant = Instant.parse("2026-03-02T18:00:00Z") // a Monday, 6 PM UTC
    private val store = InMemoryStudyStore()
    private val scheduler = FsrsScheduler(SchedulerConfig(enableFuzzing = false))
    private fun service(settings: WorkloadSettings = WorkloadSettings()) =
        StudyService(store, scheduler, settings, timeZone = { TimeZone.UTC }, now = { time })

    private fun advance(d: Duration) { time += d }

    /** Answers everything the sitting offers, "Good" for new cards and reviews. */
    private suspend fun StudyService.studyAll(rating: Rating = Rating.GOOD): Int {
        val session = startSession()
        var n = 0
        while (true) {
            val card = session.current(time) ?: break
            val after = answer(card.cardId, rating, 8_000)
            session.onAnswered(card.cardId, after, time)
            advance(10.seconds)
            n++
            check(n < 1000)
        }
        return n
    }

    @Test
    fun `an empty collection suggests getting started`() = runBlocking {
        val today = service().today()
        assertEquals(PlanMode.NOTHING_DUE, today.plan.mode)
        assertEquals(Greeting.NOTHING_DUE, today.plan.greeting)
        assertEquals(0, today.totalCards)
        assertEquals(HolidayStatus.None, today.holiday)
    }

    @Test
    fun `deck and card validation gives readable reasons`() = runBlocking {
        val s = service()
        assertEquals("Give the deck a name.", s.createDeck("  ").exceptionOrNull()?.message)
        val deck = s.createDeck("  Verbs ").getOrThrow()
        assertEquals("Verbs", s.deck(deck)!!.name)
        assertEquals("The back needs some text.", s.addCard(deck, CardContent("食べる", " ")).exceptionOrNull()?.message)
        assertTrue(s.addCard(999, CardContent("a", "b")).isFailure)
        val id = s.addCard(deck, CardContent(" 食べる ", "to eat", " たべる ")).getOrThrow()
        assertEquals(CardContent("食べる", "to eat", "たべる"), s.card(id)!!.content)
    }

    @Test
    fun `new cards are introduced up to the daily limit, then the day is done`() = runBlocking {
        val s = service(WorkloadSettings(newCardsPerDay = 5))
        s.installSampleDeck()
        val first = s.today()
        assertEquals(PlanMode.NORMAL, first.plan.mode)
        assertEquals(5, first.plan.newCount)
        assertEquals(24, first.totalCards)

        val answers = s.studyAll()
        // Each new card goes through two learning steps (1 and 10 minutes) when answered
        // Good, and the sitting keeps them until they graduate.
        assertTrue(answers >= 5, "answered $answers")
        val after = s.today()
        assertEquals(0, after.plan.newCount)
        assertEquals(NewCardPolicy.DAILY_LIMIT_REACHED, after.plan.newCardPolicy)

        val stats = s.stats()
        assertEquals(24, stats.totalCards)
        assertEquals(19, stats.newCards)
        assertEquals(5, stats.newCardsToday)
        assertEquals(answers, stats.reviewsToday)
    }

    @Test
    fun `again keeps a card in the sitting until its step is done`() = runBlocking {
        val s = service(WorkloadSettings(newCardsPerDay = 1))
        val deck = s.createDeck("D").getOrThrow()
        val id = s.addCard(deck, CardContent("猫", "cat")).getOrThrow()
        val session = s.startSession()
        assertEquals(id, session.current(time)!!.cardId)
        val again = s.answer(id, Rating.AGAIN, 5_000)
        session.onAnswered(id, again, time)
        assertEquals(MemoryPhase.LEARNING, again.phase)
        // Nothing else left, so the step is learned a little early.
        assertEquals(id, session.current(time)?.cardId)
        assertEquals(CardKind.LEARNING, session.current(time)?.kind)
        val easy = s.answer(id, Rating.EASY, 5_000)
        session.onAnswered(id, easy, time)
        assertEquals(MemoryPhase.REVIEW, easy.phase)
        assertNull(session.current(time))
        assertEquals(2, session.answered)
        assertEquals(1, session.cardsSeen)
    }

    @Test
    fun `reviews come back on later days and previews match the scheduler`() = runBlocking {
        val s = service(WorkloadSettings(newCardsPerDay = 3))
        val deck = s.createDeck("D").getOrThrow()
        repeat(3) { s.addCard(deck, CardContent("w$it", "m$it")).getOrThrow() }
        s.studyAll(Rating.EASY)
        assertEquals(0, s.stats().newCards)

        advance(30.days)
        val plan = s.today().plan
        assertEquals(3, plan.reviewCount)
        val id = plan.queue.first().cardId
        val previews = s.previews(id)
        assertEquals(Rating.entries.toSet(), previews.keys)
        assertTrue(previews.getValue(Rating.AGAIN) < previews.getValue(Rating.EASY))
        s.studyAll()
        assertEquals(PlanMode.DONE_FOR_TODAY, s.today().plan.mode)
    }

    @Test
    fun `starting a session records deferred cards, looking at Home does not`() = runBlocking {
        val small = WorkloadSettings(targetSessionMinutes = 1, maxSessionMinutes = 1, newCardsPerDay = 40)
        val s = service(small)
        val deck = s.createDeck("Big").getOrThrow()
        repeat(40) { s.addCard(deck, CardContent("w$it", "m$it")).getOrThrow() }
        // Introduce all 40 cards over several sittings, answering Easy so they graduate.
        var sittings = 0
        while (s.stats().newCards > 0 && sittings++ < 100) { s.studyAll(Rating.EASY); advance(10.seconds) }
        assertEquals(0, s.stats().newCards)

        advance(40.days)
        val plan = s.today().plan
        assertTrue(plan.deferred.isNotEmpty())
        assertTrue(plan.deferred.all { it.reason == DeferralReason.SESSION_LIMIT })
        val ids = plan.deferred.map { it.cardId }
        assertTrue(store.deferralStates(ids).isEmpty(), "viewing the plan changes nothing")

        s.startSession()
        val recorded = store.deferralStates(ids)
        assertEquals(ids.toSet(), recorded.keys)
        assertTrue(recorded.values.all { it.studyDaysDeferred == 1 })
        assertNotNull(store.recoveryState(), "a backlog bigger than a session starts a recovery")

        // A second sitting the same day does not count twice.
        s.startSession()
        assertTrue(store.deferralStates(ids).values.all { it.studyDaysDeferred == 1 })
    }

    @Test
    fun `holiday mode pauses new cards and can be ended`() = runBlocking {
        val s = service()
        s.installSampleDeck()
        val today = s.studyDay()
        val h = s.scheduleHoliday(HolidayMode.FULL, today, today.plus(6, DateTimeUnit.DAY)).getOrThrow()
        val during = s.today()
        assertEquals(PlanMode.HOLIDAY, during.plan.mode)
        assertEquals(0, during.plan.newCount)
        assertTrue(during.holiday is HolidayStatus.Active)

        assertTrue(s.scheduleHoliday(HolidayMode.CASUAL, today.plus(2, DateTimeUnit.DAY), today.plus(3, DateTimeUnit.DAY)).isFailure)

        s.endHolidayNow()
        assertTrue(s.holidays().none { it.id == h.id && it.covers(today) })
        assertEquals(PlanMode.NORMAL, s.today().plan.mode)
    }

    @Test
    fun `an indefinite pause has no end and an upcoming holiday can be cancelled`() = runBlocking {
        val s = service()
        val later = s.studyDay().plus(10, DateTimeUnit.DAY)
        val pause = s.scheduleHoliday(HolidayMode.INDEFINITE_PAUSE, later, LocalDate(2030, 1, 1)).getOrThrow()
        assertNull(pause.endsOn)
        assertTrue(s.holidayStatus() is HolidayStatus.Upcoming)
        s.cancelUpcomingHoliday(pause.id)
        assertTrue(s.holidays().isEmpty())
    }

    @Test
    fun `editing a card keeps its memory, suspending removes it from study`() = runBlocking {
        val s = service(WorkloadSettings(newCardsPerDay = 1))
        val deck = s.createDeck("D").getOrThrow()
        val id = s.addCard(deck, CardContent("犬", "dog")).getOrThrow()
        s.studyAll(Rating.EASY)
        val memory = s.memoryState(id)
        s.updateCard(id, CardContent("犬", "dog (いぬ)")).getOrThrow()
        assertEquals(memory, s.memoryState(id))
        s.setSuspended(id, true)
        advance(60.days)
        assertEquals(0, s.today().plan.queue.size)
        assertEquals(0, s.stats().totalCards)
    }

    @Test
    fun `every write bumps the change counter`() = runBlocking {
        val s = service()
        val before = s.changes.value
        s.createDeck("D").getOrThrow()
        assertTrue(s.changes.value > before)
        val v = s.changes.value
        s.today()
        assertEquals(v, s.changes.value)
    }

    @Test
    fun `stats show the last week and the forecast`() = runBlocking {
        val s = service(WorkloadSettings(newCardsPerDay = 4))
        val deck = s.createDeck("D").getOrThrow()
        repeat(4) { s.addCard(deck, CardContent("w$it", "m$it")).getOrThrow() }
        s.studyAll()
        val stats = s.stats()
        assertEquals(7, stats.last7Days.size)
        assertEquals(stats.reviewsToday, stats.last7Days.last().count)
        assertEquals(7, stats.forecast.size)
        assertEquals(4, stats.forecast.sumOf { it.count } + stats.dueToday)
        assertEquals(1, stats.studyDaysLast30)
        assertNull(stats.retentionLast30)
        assertFalse(stats.minutesToday < 0)
    }
}
