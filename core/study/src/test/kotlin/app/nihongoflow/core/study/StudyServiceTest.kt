package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.workload.CardKind
import app.nihongoflow.core.workload.PlanMode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StudyServiceTest {

    private class TestClock(var now: Instant) : Clock {
        override fun now() = now
        fun advance(d: Duration) { now += d }
    }

    private val clock = TestClock(Instant.parse("2026-05-04T18:00:00Z"))
    private val store = InMemoryStore()
    private var ids = 0
    private val service = StudyService(store, store, clock, { TimeZone.UTC }, { "id-${++ids}" })

    private suspend fun vocab(deckId: String, word: String, reading: String, meaning: String, allowDuplicate: Boolean = false) =
        service.saveNote(
            deckId, NoteKind.VOCABULARY,
            mapOf(Fields.WORD to word, Fields.READING to reading, Fields.MEANING to meaning),
            allowDuplicate = allowDuplicate,
        )

    @Test
    fun `vocabulary note makes recognition and production cards`() = runTest {
        val deck = service.createDeck("Words")
        val saved = assertIs<SaveNoteResult.Saved>(vocab(deck.id, "食べる", "たべる", "to eat"))
        assertEquals(2, saved.cardsCreated)
        val cards = store.cards(saved.note.id)
        assertEquals(setOf(CardTemplate.RECOGNITION, CardTemplate.PRODUCTION), cards.map { it.template }.toSet())
    }

    @Test
    fun `duplicates are detected by word and reading, not by shared kanji`() = runTest {
        val deck = service.createDeck("Words")
        vocab(deck.id, "食べる", "たべる", "to eat")
        // Same word, reading typed in katakana and with full-width spacing: same item.
        assertIs<SaveNoteResult.PossibleDuplicate>(vocab(deck.id, "食べる ", "タベル", "eat"))
        // Shares 食 but is a different word.
        assertIs<SaveNoteResult.Saved>(vocab(deck.id, "食事", "しょくじ", "meal"))
        // Learner can still save on purpose.
        assertIs<SaveNoteResult.Saved>(vocab(deck.id, "食べる", "たべる", "to eat", allowDuplicate = true))
    }

    @Test
    fun `editing a note keeps its cards and review history`() = runTest {
        val deck = service.createDeck("Words")
        val note = assertIs<SaveNoteResult.Saved>(vocab(deck.id, "水", "みず", "water")).note
        val card = store.cards(note.id).first { it.template == CardTemplate.RECOGNITION }
        service.answer(card.id, Rating.GOOD, 3000)
        val before = store.memory(card.id)

        val edited = service.saveNote(
            deck.id, NoteKind.VOCABULARY,
            mapOf(Fields.WORD to "水", Fields.READING to "みず", Fields.MEANING to "water (cold)"),
            existingId = note.id,
        )
        assertIs<SaveNoteResult.Saved>(edited)
        assertEquals(0, edited.cardsCreated)
        assertEquals(before, store.memory(card.id))
        assertEquals(1, store.reviewsFor(card.id).size)
        assertEquals("water (cold)", store.note(note.id)!!.field(Fields.MEANING))
    }

    @Test
    fun `removing a cloze suspends its card instead of deleting history`() = runTest {
        val deck = service.createDeck("Sentences")
        val note = assertIs<SaveNoteResult.Saved>(
            service.saveNote(deck.id, NoteKind.CLOZE, mapOf(Fields.TEXT to "{{c1::駅}}まで{{c2::歩きます}}。")),
        ).note
        val c2 = store.cards(note.id).single { it.clozeNumber == 2 }
        service.answer(c2.id, Rating.GOOD, 2000)

        service.saveNote(deck.id, NoteKind.CLOZE, mapOf(Fields.TEXT to "{{c1::駅}}まで歩きます。"), existingId = note.id)
        val after = store.cards(note.id).single { it.clozeNumber == 2 }
        assertTrue(after.suspended)
        assertEquals(1, store.reviewsFor(c2.id).size)

        service.saveNote(deck.id, NoteKind.CLOZE, mapOf(Fields.TEXT to "{{c1::駅}}まで{{c2::歩きます}}。"), existingId = note.id)
        val restored = store.cards(note.id).single { it.clozeNumber == 2 }
        assertEquals(c2.id, restored.id)
        assertTrue(!restored.suspended)
    }

    @Test
    fun `invalid notes are explained, not saved`() = runTest {
        val deck = service.createDeck("Words")
        assertIs<SaveNoteResult.Invalid>(service.saveNote(deck.id, NoteKind.VOCABULARY, mapOf(Fields.WORD to "水")))
        assertIs<SaveNoteResult.Invalid>(service.saveNote(deck.id, NoteKind.CLOZE, mapOf(Fields.TEXT to "no cloze here")))
        assertTrue(store.allNotes().isEmpty())
    }

    @Test
    fun `a full study session works end to end`() = runTest {
        val deck = StarterDeck.install(service)
        assertEquals(StarterDeck.noteCount, store.notes(deck.id).size)
        // Installing twice does not duplicate.
        StarterDeck.install(service)
        assertEquals(1, store.decks().size)

        val plan = service.plan()
        assertEquals(PlanMode.NORMAL, plan.mode)
        assertEquals(5, plan.newCount) // default new cards per day
        val session = service.startSession(plan)

        var shown = 0
        while (true) {
            val id = session.next(clock.now) ?: break
            assertNotNull(service.cardFace(id))
            val preview = service.preview(id)
            assertEquals(4, preview.size)
            val rating = if (shown == 0) Rating.AGAIN else Rating.GOOD
            val state = service.answer(id, rating, 8000)
            session.onAnswered(id, state, clock.now)
            clock.advance(2.minutes)
            shown++
            assertTrue(shown < 100)
        }
        // Again and Good both leave cards in learning steps, so each new card came back.
        assertTrue(shown > plan.queue.size, "shown $shown for ${plan.queue.size} planned")
        assertEquals(shown, store.reviewCount())

        // Same day: new card limit reached.
        val later = service.plan()
        assertEquals(0, later.newCount)
    }

    @Test
    fun `disabled review directions are left out`() = runTest {
        val deck = service.createDeck("Words")
        vocab(deck.id, "水", "みず", "water")
        service.saveSettings(service.settings().copy(enabledDirections = setOf(ReviewDirection.JAPANESE_TO_ENGLISH)))
        val plan = service.plan()
        val templates = plan.queue.map { store.card(it.cardId)!!.template }.toSet()
        assertEquals(setOf(CardTemplate.RECOGNITION), templates)
    }

    @Test
    fun `holiday mode pauses new cards and a return eases back in`() = runTest {
        StarterDeck.install(service)
        // Study for a week.
        repeat(7) {
            val session = service.startSession(service.plan())
            while (true) {
                val id = session.next(clock.now) ?: break
                session.onAnswered(id, service.answer(id, Rating.GOOD, 6000), clock.now)
                clock.advance(30.seconds)
            }
            clock.advance(1.days)
        }
        val today = service.today()
        service.scheduleHoliday(HolidayMode.FULL, today, today.plus(13, DateTimeUnit.DAY)).getOrThrow()
        assertIs<HolidayStatus.Active>(service.holidayStatus())
        val onHoliday = service.plan()
        assertEquals(PlanMode.HOLIDAY, onHoliday.mode)
        assertEquals(0, onHoliday.newCount)

        val reviewsBefore = store.reviewCount()
        clock.advance(14.days)
        assertEquals(HolidayStatus.None, service.holidayStatus())
        assertEquals(reviewsBefore, store.reviewCount())
        val back = service.plan()
        assertTrue(back.mode == PlanMode.RECOVERY || back.mode == PlanMode.NORMAL)
        assertEquals(app.nihongoflow.core.workload.Greeting.WELCOME_BACK_FROM_HOLIDAY, back.greeting)
    }

    @Test
    fun `ending a holiday early returns to normal today`() = runTest {
        val today = service.today()
        service.scheduleHoliday(HolidayMode.INDEFINITE_PAUSE, today.plus(-3, DateTimeUnit.DAY), null).getOrThrow()
        assertIs<HolidayStatus.Active>(service.holidayStatus())
        service.endHolidayNow()
        assertEquals(HolidayStatus.None, service.holidayStatus())
    }

    @Test
    fun `statistics separate actual results from estimates and never reset`() = runTest {
        StarterDeck.install(service)
        repeat(3) {
            val session = service.startSession(service.plan())
            while (true) {
                val id = session.next(clock.now) ?: break
                session.onAnswered(id, service.answer(id, Rating.GOOD, 6000), clock.now)
                clock.advance(30.seconds)
            }
            clock.advance(1.days)
        }
        val stats = service.stats()
        assertEquals(3, stats.daysStudied)
        assertTrue(stats.totalReviews > 0)
        assertNotNull(stats.estimatedRetentionNow)
        assertTrue(stats.itemsStarted.getValue(NoteKind.VOCABULARY) > 0)

        clock.advance(20.days) // a long gap
        val later = service.stats()
        assertEquals(stats.daysStudied, later.daysStudied)
        assertEquals(stats.totalReviews, later.totalReviews)
        // The estimate moves with time; the history does not.
        assertTrue(later.estimatedRetentionNow!! < stats.estimatedRetentionNow!!)
    }

    @Test
    fun `review session brings back a card answered Again`() = runTest {
        val deck = service.createDeck("Words")
        vocab(deck.id, "水", "みず", "water")
        service.saveSettings(service.settings().copy(enabledDirections = setOf(ReviewDirection.JAPANESE_TO_ENGLISH)))
        val plan = service.plan()
        assertEquals(1, plan.queue.size)
        assertEquals(CardKind.NEW, plan.queue.single().kind)
        val session = service.startSession(plan)
        val id = session.next(clock.now)!!
        val state = service.answer(id, Rating.AGAIN, 4000)
        assertEquals(MemoryPhase.LEARNING, state.phase)
        session.onAnswered(id, state, clock.now)
        // Shown again (here immediately, as nothing else is left).
        assertEquals(id, session.next(clock.now))
        assertNull(session.next(clock.now + 1.hours))
    }

    @Test
    fun `deleting a deck removes its notes and cards`() = runTest {
        val deck = service.createDeck("Temp")
        vocab(deck.id, "水", "みず", "water")
        service.deleteDeck(deck.id)
        assertTrue(store.allNotes().isEmpty())
        assertTrue(store.decks().isEmpty())
    }
}
