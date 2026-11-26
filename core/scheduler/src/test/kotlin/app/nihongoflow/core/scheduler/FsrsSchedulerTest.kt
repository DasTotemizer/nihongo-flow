package app.nihongoflow.core.scheduler

import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Behaviour tests for the FSRS wrapper. The first two tests reproduce reference cases from
 * java-fsrs's own test suite (FSRSTest.testReviewCard and FSRSTest.testMemoState), run
 * through Nihongo Flow's types, to prove the wrapper converts state without losing anything.
 */
class FsrsSchedulerTest {

    private val start = Instant.parse("2022-11-29T12:30:00Z")
    private val noFuzz = FsrsScheduler(SchedulerConfig(enableFuzzing = false))

    private fun daysBetween(a: Instant, b: Instant) = (b - a).inWholeDays

    @Test
    fun `reference interval sequence matches java-fsrs`() {
        val ratings = listOf(
            Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
            Rating.AGAIN, Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD,
        )
        var state = MemoryState.new(start)
        var reviewAt = start
        val intervals = mutableListOf<Long>()
        for (rating in ratings) {
            state = noFuzz.review(state, rating, reviewAt)
            intervals += daysBetween(state.lastReview!!, state.due)
            reviewAt = state.due
        }
        assertEquals(listOf<Long>(0, 4, 14, 45, 135, 372, 0, 0, 2, 5, 10, 20, 40), intervals)
    }

    @Test
    fun `reference memory state matches java-fsrs`() {
        val scheduler = FsrsScheduler() // library defaults, fuzzing on, as in the reference
        val ratings = listOf(Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD)
        val gaps = listOf(0, 0, 1, 3, 8, 21)
        var state = MemoryState.new(start)
        var reviewAt = start
        for (i in ratings.indices) {
            reviewAt += gaps[i].days
            state = scheduler.review(state, ratings[i], reviewAt)
        }
        state = scheduler.review(state, Rating.GOOD, reviewAt)
        assertTrue(abs(state.stability!! - 49.4472) < 0.0001, "stability was ${state.stability}")
        assertTrue(abs(state.difficulty!! - 6.8271) < 0.0001, "difficulty was ${state.difficulty}")
    }

    @Test
    fun `each rating on a new card gives the expected first steps`() {
        val new = MemoryState.new(start)
        val again = noFuzz.review(new, Rating.AGAIN, start)
        val hard = noFuzz.review(new, Rating.HARD, start)
        val good = noFuzz.review(new, Rating.GOOD, start)
        val easy = noFuzz.review(new, Rating.EASY, start)

        // Default learning steps are 1 and 10 minutes.
        assertEquals(MemoryPhase.LEARNING, again.phase)
        assertEquals(start + 1.minutes, again.due)
        assertEquals(MemoryPhase.LEARNING, hard.phase)
        assertEquals(start + 330.seconds, hard.due) // 5.5 minutes, midway between the two steps
        assertEquals(MemoryPhase.LEARNING, good.phase)
        assertEquals(1, good.step)
        assertEquals(start + 10.minutes, good.due)
        assertEquals(MemoryPhase.REVIEW, easy.phase)
        assertTrue(easy.due >= start + 1.days)

        // Every answer creates real memory state and records the review time.
        for (s in listOf(again, hard, good, easy)) {
            assertNotNull(s.stability)
            assertNotNull(s.difficulty)
            assertEquals(start, s.lastReview)
        }
        // Easier answers never give lower stability.
        assertTrue(again.stability!! < hard.stability!!)
        assertTrue(hard.stability!! < good.stability!!)
        assertTrue(good.stability!! < easy.stability!!)
    }

    @Test
    fun `answers on a review card are ordered by interval`() {
        val review = graduate()
        val at = review.due
        val preview = noFuzz.preview(review, at)
        val again = preview.getValue(Rating.AGAIN)
        val hard = preview.getValue(Rating.HARD)
        val good = preview.getValue(Rating.GOOD)
        val easy = preview.getValue(Rating.EASY)
        assertTrue(again < hard && hard < good && good < easy, "preview was $preview")

        val lapsed = noFuzz.review(review, Rating.AGAIN, at)
        assertEquals(MemoryPhase.RELEARNING, lapsed.phase)
        assertEquals(at + 10.minutes, lapsed.due)
    }

    @Test
    fun `preview matches the real result when fuzzing is off`() {
        val review = graduate()
        val at = review.due + 2.days
        val preview = noFuzz.preview(review, at)
        for (rating in Rating.entries) {
            assertEquals(preview.getValue(rating), noFuzz.review(review, rating, at).due, "rating $rating")
        }
    }

    @Test
    fun `real elapsed time is used, so a late review differs from an on-time one`() {
        val review = graduate()
        val onTime = noFuzz.review(review, Rating.GOOD, review.due)
        val threeWeeksLate = noFuzz.review(review, Rating.GOOD, review.due + 21.days)

        // Recalling after a longer gap is stronger evidence, so stability grows more.
        assertTrue(threeWeeksLate.stability!! > onTime.stability!!)
        assertEquals(review.due + 21.days, threeWeeksLate.lastReview)

        // Retrievability keeps falling while the learner is away; nothing pauses the clock.
        val r0 = noFuzz.retrievability(review, review.due)!!
        val r7 = noFuzz.retrievability(review, review.due + 7.days)!!
        val r30 = noFuzz.retrievability(review, review.due + 30.days)!!
        assertTrue(r0 > r7 && r7 > r30, "r0=$r0 r7=$r7 r30=$r30")
    }

    @Test
    fun `desired retention changes the interval`() {
        val review = graduate()
        val at = review.due
        fun goodInterval(retention: Double) =
            FsrsScheduler(SchedulerConfig(desiredRetention = retention, enableFuzzing = false))
                .review(review, Rating.GOOD, at).due - at

        assertTrue(goodInterval(0.80) > goodInterval(0.90))
        assertTrue(goodInterval(0.90) > goodInterval(0.95))
    }

    @Test
    fun `retrievability at the due date is close to desired retention`() {
        val review = graduate()
        val next = noFuzz.review(review, Rating.GOOD, review.due)
        val r = noFuzz.retrievability(next, next.due)!!
        // Intervals are rounded to whole days, so allow a small tolerance.
        assertTrue(abs(r - 0.9) < 0.02, "retrievability at due was $r")
    }

    @Test
    fun `new cards have no retrievability`() {
        assertNull(noFuzz.retrievability(MemoryState.new(start), start + 3.days))
    }

    @Test
    fun `reviewing does not modify the input state`() {
        val review = graduate()
        val copy = review.copy()
        noFuzz.review(review, Rating.AGAIN, review.due)
        assertEquals(copy, review)
    }

    @Test
    fun `a review earlier than the last review is rejected`() {
        val review = graduate()
        assertFailsWith<IllegalArgumentException> {
            noFuzz.review(review, Rating.GOOD, review.lastReview!! - 1.minutes)
        }
    }

    @Test
    fun `invalid configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> { SchedulerConfig(desiredRetention = 0.5) }
        assertFailsWith<IllegalArgumentException> { SchedulerConfig(parameters = listOf(1.0, 2.0)) }
        val outOfBounds = ParameterSafeguards.LOWER_BOUNDS.toMutableList().also { it[4] = 0.5 }
        assertFailsWith<IllegalArgumentException> { SchedulerConfig(parameters = outOfBounds) }
        val withNaN = ParameterSafeguards.LOWER_BOUNDS.toMutableList().also { it[0] = Double.NaN }
        assertTrue(ParameterSafeguards.problems(withNaN).isNotEmpty())
    }

    @Test
    fun `custom parameters within bounds are accepted and used`() {
        val custom = listOf(
            0.4, 1.2, 3.0, 15.0, 7.0, 0.6, 2.0, 0.01, 1.5, 0.1, 1.0,
            1.9, 0.1, 0.3, 2.3, 0.2, 3.0, 0.7, 0.3, 0.1, 0.2,
        )
        assertTrue(ParameterSafeguards.problems(custom).isEmpty())
        val s = FsrsScheduler(SchedulerConfig(parameters = custom, enableFuzzing = false))
        val reviewed = s.review(MemoryState.new(start), Rating.AGAIN, start)
        assertTrue(abs(reviewed.stability!! - 0.4) < 1e-9) // w0 is the initial stability for Again
    }

    @Test
    fun `optimisation needs enough real history`() {
        assertTrue(!ParameterSafeguards.hasEnoughHistoryForOptimisation(511))
        assertTrue(ParameterSafeguards.hasEnoughHistoryForOptimisation(512))
    }

    /** A card answered Good through both learning steps, now in Review. */
    private fun graduate(): MemoryState {
        var s = MemoryState.new(start)
        s = noFuzz.review(s, Rating.GOOD, start)
        s = noFuzz.review(s, Rating.GOOD, s.due)
        assertEquals(MemoryPhase.REVIEW, s.phase)
        return s
    }
}
