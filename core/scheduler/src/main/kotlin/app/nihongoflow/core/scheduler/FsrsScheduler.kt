package app.nihongoflow.core.scheduler

import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import java.time.Duration as JavaDuration
import io.github.openspacedrepetition.Rating as FsrsRating

/**
 * [MemoryScheduler] backed by java-fsrs, the Java implementation of FSRS-6 maintained by
 * the open-spaced-repetition project (MIT licence). This class only converts between
 * Nihongo Flow's types and the library's; all scheduling maths is the library's own.
 *
 * Thread-safe: the library's scheduler holds a random number generator for fuzzing, so
 * access is serialised.
 */
class FsrsScheduler(val config: SchedulerConfig = SchedulerConfig()) : MemoryScheduler {

    private val scheduler: Scheduler = buildScheduler(config, fuzz = config.enableFuzzing)

    // Previews never fuzz, so showing them does not consume random numbers either.
    private val previewScheduler: Scheduler = buildScheduler(config, fuzz = false)

    private val lock = Any()

    override fun review(state: MemoryState, rating: Rating, reviewedAt: Instant): MemoryState {
        state.lastReview?.let {
            require(reviewedAt >= it) {
                "A review cannot be earlier than the card's last review ($it); got $reviewedAt"
            }
        }
        val card = state.toFsrsCard()
        val result = synchronized(lock) {
            scheduler.reviewCard(card, rating.toFsrs(), reviewedAt.toJavaInstant())
        }
        return result.card().toMemoryState()
    }

    override fun preview(state: MemoryState, at: Instant): Map<Rating, Instant> {
        val card = state.toFsrsCard()
        return Rating.entries.associateWith { rating ->
            synchronized(lock) {
                previewScheduler.reviewCard(card, rating.toFsrs(), at.toJavaInstant())
            }.card().due.toKotlinInstant()
        }
    }

    override fun retrievability(state: MemoryState, at: Instant): Double? {
        if (state.lastReview == null || state.stability == null) return null
        return synchronized(lock) { scheduler.getCardRetrievability(state.toFsrsCard(), at.toJavaInstant()) }
    }

    private companion object {
        // java-fsrs uses this id only for its own review logs; ours are tracked separately.
        // Setting it explicitly also avoids the library's id generator, which sleeps.
        const val LIBRARY_CARD_ID = 0

        fun buildScheduler(config: SchedulerConfig, fuzz: Boolean): Scheduler {
            val builder = Scheduler.builder()
                .desiredRetention(config.desiredRetention)
                .learningSteps(config.learningSteps.map { it.toJava() }.toTypedArray())
                .relearningSteps(config.relearningSteps.map { it.toJava() }.toTypedArray())
                .maximumInterval(config.maximumIntervalDays)
                .enableFuzzing(fuzz)
            config.parameters?.let { builder.parameters(it.toDoubleArray()) }
            return builder.build()
        }

        fun kotlin.time.Duration.toJava(): JavaDuration = JavaDuration.ofMillis(inWholeMilliseconds)

        fun Rating.toFsrs(): FsrsRating = when (this) {
            Rating.AGAIN -> FsrsRating.AGAIN
            Rating.HARD -> FsrsRating.HARD
            Rating.GOOD -> FsrsRating.GOOD
            Rating.EASY -> FsrsRating.EASY
        }

        fun MemoryState.toFsrsCard(): Card {
            val builder = Card.builder().cardId(LIBRARY_CARD_ID).due(due.toJavaInstant())
            when (phase) {
                // The library represents a never-reviewed card as Learning, step 0, with no
                // stability or difficulty yet.
                MemoryPhase.NEW -> builder.state(State.LEARNING).step(0)
                MemoryPhase.LEARNING -> builder.state(State.LEARNING).step(step ?: 0)
                MemoryPhase.REVIEW -> builder.state(State.REVIEW).step(null)
                MemoryPhase.RELEARNING -> builder.state(State.RELEARNING).step(step ?: 0)
            }
            if (phase != MemoryPhase.NEW) {
                builder.stability(stability).difficulty(difficulty)
                builder.lastReview(lastReview?.toJavaInstant())
            }
            return builder.build()
        }

        fun Card.toMemoryState(): MemoryState = MemoryState(
            phase = when (state) {
                State.LEARNING -> MemoryPhase.LEARNING
                State.REVIEW -> MemoryPhase.REVIEW
                State.RELEARNING -> MemoryPhase.RELEARNING
                null -> error("java-fsrs returned a card without a state")
            },
            step = step,
            stability = stability,
            difficulty = difficulty,
            due = due.toKotlinInstant(),
            lastReview = lastReview?.toKotlinInstant(),
        )
    }
}
