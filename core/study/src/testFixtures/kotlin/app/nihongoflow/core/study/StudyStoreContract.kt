package app.nihongoflow.core.study

import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.workload.DeferralState
import app.nihongoflow.core.workload.RecoveryState
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Behaviour every [StudyStore] must have. Each check takes a fresh, empty store. Test
 * classes for each implementation call every check (see [all]).
 */
object StudyStoreContract {
    val T0: Instant = Instant.parse("2026-03-02T10:00:00Z")

    val all: Map<String, (StudyStore) -> Unit> = mapOf(
        "decks are stored, renamed and listed by name" to ::decks,
        "cards start new and keep their introduction order" to ::cards,
        "suspended cards are never scheduled" to ::suspension,
        "a review updates memory, appends history and clears deferral" to ::review,
        "deleting a deck removes its cards and their history" to ::deleteDeck,
        "bookkeeping round-trips" to ::bookkeeping,
        "deck summaries count new and due cards" to ::summaries,
    )

    private fun reviewed(due: Instant, last: Instant) = MemoryState(MemoryPhase.REVIEW, null, 5.0, 5.0, due, last)

    fun decks(store: StudyStore) = runBlocking {
        val b = store.insertDeck("Verbs", "", T0)
        val a = store.insertDeck("adjectives", "describing words", T0)
        assertEquals(listOf("adjectives", "Verbs"), store.decks().map { it.name })
        store.renameDeck(b, "Verbs (N5)", "godan and ichidan")
        val renamed = store.deck(b)!!
        assertEquals("Verbs (N5)", renamed.name)
        assertEquals("godan and ichidan", renamed.description)
        assertEquals(T0, renamed.createdAt)
        assertEquals("describing words", store.deck(a)!!.description)
        assertNull(store.deck(999))
    }

    fun cards(store: StudyStore) = runBlocking {
        val d = store.insertDeck("D", "", T0)
        val first = store.insertCard(d, CardContent("一", "one", "いち"), T0)
        val second = store.insertCard(d, CardContent("二", "two", "に", "note"), T0 + 1.hours)
        assertEquals(listOf(first, second), store.newCardIds(10))
        assertEquals(listOf(first), store.newCardIds(1))
        assertEquals(MemoryState.new(T0), store.memoryState(first))
        val c = store.card(second)!!
        assertEquals(CardContent("二", "two", "に", "note"), c.content)
        assertEquals(d, c.deckId)
        assertTrue(store.card(first)!!.newPosition < c.newPosition)
        store.updateCardContent(second, CardContent("二", "two (2)"))
        assertEquals("two (2)", store.card(second)!!.content.back)
        assertEquals(listOf(first, second), store.cards(d).map { it.id })
        assertTrue(store.dueCards(T0 + 365.days).isEmpty(), "new cards are not due")
        store.deleteCard(first)
        assertNull(store.card(first))
        assertNull(store.memoryState(first))
        assertEquals(listOf(second), store.newCardIds(10))
    }

    fun suspension(store: StudyStore) = runBlocking {
        val d = store.insertDeck("D", "", T0)
        val a = store.insertCard(d, CardContent("a", "a"), T0)
        val b = store.insertCard(d, CardContent("b", "b"), T0)
        store.applyReview(ReviewRecord(b, Rating.GOOD, T0, 1000, MemoryState.new(T0), reviewed(T0 + 1.days, T0)))
        store.setSuspended(a, true)
        store.setSuspended(b, true)
        assertTrue(store.newCardIds(10).isEmpty())
        assertTrue(store.dueCards(T0 + 10.days).isEmpty())
        assertTrue(store.dueTimesBetween(T0, T0 + 10.days).isEmpty())
        assertTrue(store.allMemoryStates().isEmpty())
        assertTrue(store.card(a)!!.suspended)
        store.setSuspended(a, false)
        assertEquals(listOf(a), store.newCardIds(10))
    }

    fun review(store: StudyStore) = runBlocking {
        val d = store.insertDeck("D", "", T0)
        val id = store.insertCard(d, CardContent("a", "a"), T0)
        store.saveDeferralStates(mapOf(id to DeferralState(LocalDate(2026, 3, 1), LocalDate(2026, 3, 1), 1)))
        val after = reviewed(T0 + 3.days, T0)
        store.applyReview(ReviewRecord(id, Rating.GOOD, T0, 4200, MemoryState.new(T0), after))
        assertEquals(after, store.memoryState(id))
        assertTrue(store.deferralStates(listOf(id)).isEmpty())
        val history = store.reviewsSince(T0)
        assertEquals(1, history.size)
        assertEquals(Rating.GOOD, history[0].rating)
        assertEquals(4200L, history[0].durationMillis)
        assertEquals(MemoryState.new(T0), history[0].stateBefore)
        assertEquals(after, history[0].stateAfter)
        assertTrue(store.reviewsSince(T0 + 1.hours).isEmpty())
        assertEquals(T0, store.lastReviewTime())
        assertEquals(listOf(4200L), store.recentDurations(10))
        assertTrue(store.newCardIds(10).isEmpty())
        assertTrue(store.dueCards(T0 + 2.days).isEmpty())
        assertEquals(listOf(id), store.dueCards(T0 + 4.days).map { it.first })
        assertEquals(listOf(T0 + 3.days), store.dueTimesBetween(T0 + 1.days, T0 + 5.days))
        assertTrue(store.dueTimesBetween(T0 + 4.days, T0 + 5.days).isEmpty())
    }

    fun deleteDeck(store: StudyStore) = runBlocking {
        val d = store.insertDeck("D", "", T0)
        val keep = store.insertDeck("Keep", "", T0)
        val id = store.insertCard(d, CardContent("a", "a"), T0)
        val kept = store.insertCard(keep, CardContent("b", "b"), T0)
        store.applyReview(ReviewRecord(id, Rating.GOOD, T0, 1000, MemoryState.new(T0), reviewed(T0 + 1.days, T0)))
        store.deleteDeck(d)
        assertNull(store.deck(d))
        assertNull(store.card(id))
        assertTrue(store.reviewsSince(T0).isEmpty())
        assertEquals(listOf(kept), store.newCardIds(10))
    }

    fun bookkeeping(store: StudyStore) = runBlocking {
        val d = store.insertDeck("D", "", T0)
        val a = store.insertCard(d, CardContent("a", "a"), T0)
        val b = store.insertCard(d, CardContent("b", "b"), T0)
        val day = LocalDate(2026, 3, 2)
        val sa = DeferralState(day, day, 1)
        store.saveDeferralStates(mapOf(a to sa))
        store.saveDeferralStates(mapOf(b to sa.copy(studyDaysDeferred = 2)))
        assertEquals(mapOf(a to sa, b to sa.copy(studyDaysDeferred = 2)), store.deferralStates(listOf(a, b, 99)))
        assertNull(store.recoveryState())
        val r = RecoveryState(day, 40, easeIn = true, studyDaysCompleted = 1, lastCountedStudyDay = day)
        store.saveRecoveryState(r)
        assertEquals(r, store.recoveryState())
        store.saveRecoveryState(null)
        assertNull(store.recoveryState())
        val h1 = HolidayPeriod("h1", HolidayMode.FULL, LocalDate(2026, 4, 1), LocalDate(2026, 4, 7))
        val h2 = HolidayPeriod("h2", HolidayMode.INDEFINITE_PAUSE, LocalDate(2026, 5, 1), null)
        store.saveHolidays(listOf(h2, h1))
        assertEquals(setOf(h1, h2), store.holidays().toSet())
        store.saveHolidays(listOf(h1))
        assertEquals(listOf(h1), store.holidays())
    }

    fun summaries(store: StudyStore) = runBlocking {
        val d = store.insertDeck("D", "", T0)
        val empty = store.insertDeck("Empty", "", T0)
        val a = store.insertCard(d, CardContent("a", "a"), T0)
        store.insertCard(d, CardContent("b", "b"), T0)
        val c = store.insertCard(d, CardContent("c", "c"), T0)
        store.applyReview(ReviewRecord(a, Rating.GOOD, T0, 1000, MemoryState.new(T0), reviewed(T0 + 1.days, T0)))
        store.applyReview(ReviewRecord(c, Rating.GOOD, T0, 1000, MemoryState.new(T0), reviewed(T0 + 9.days, T0)))
        val s = store.deckSummaries(dueBefore = T0 + 2.days).associateBy { it.deck.id }
        assertEquals(3, s.getValue(d).cardCount)
        assertEquals(1, s.getValue(d).newCount)
        assertEquals(1, s.getValue(d).dueCount)
        assertEquals(0, s.getValue(empty).cardCount)
    }
}
