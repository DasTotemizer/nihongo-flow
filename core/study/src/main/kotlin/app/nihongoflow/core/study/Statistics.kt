package app.nihongoflow.core.study

import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.RetrievabilityEstimator
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.model.StudyDayClock
import app.nihongoflow.core.workload.SessionPlan
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

/**
 * Progress figures. Actual results (from real reviews) and estimates (from the FSRS model)
 * are kept in separate fields so the UI never presents one as the other.
 */
data class ProgressStats(
    // What's in the collection, studied or not.
    val notesByKind: Map<NoteKind, Int>,
    val totalCards: Int,
    /** Active cards never studied yet. */
    val cardsNotStarted: Int,
    val suspendedCards: Int,

    // Positive milestones; nothing here ever resets.
    val itemsStarted: Map<NoteKind, Int>,
    /** Notes with at least one card whose memory has lasted 21+ days (FSRS stability). */
    val itemsWellKnown: Map<NoteKind, Int>,
    val totalReviews: Int,
    val daysStudied: Int,
    val totalStudyMinutes: Int,

    // Actual history.
    val reviewsLast30Days: Int,
    /** Share of review-phase answers in the last 30 days that were not Again. Null with too few answers. */
    val actualRecallLast30Days: Double?,
    val reviewedToday: Int,

    // Estimates from the memory model.
    /** Mean predicted recall probability now, across cards studied at least once. */
    val estimatedRetentionNow: Double?,
    /** Cards whose predicted recall is currently below 80%. */
    val cardsLikelyFading: Int,

    // True workload accounting for today.
    val dueByFsrsToday: Int,
    val plannedToday: Int,
    val deferredByWorkloadToday: Int,

    /** Notes answered Again most often recently, hardest first. */
    val difficultNotes: List<Pair<Note, Int>>,
)

object Statistics {

    suspend fun compute(
        now: Instant,
        notes: List<Note>,
        cards: List<Card>,
        memory: Map<CardId, MemoryState>,
        reviews: List<ReviewRecord>,
        scheduler: RetrievabilityEstimator,
        dayClock: StudyDayClock,
        today: SessionPlan,
    ): ProgressStats {
        val started = mutableMapOf<NoteKind, Int>()
        val wellKnown = mutableMapOf<NoteKind, Int>()
        val noteOfCard = mutableMapOf<CardId, Note>()
        val cardsByNote = cards.groupBy { it.noteId }
        for (note in notes) {
            val noteCards = cardsByNote[note.id].orEmpty()
            noteCards.forEach { noteOfCard[it.id] = note }
            val states = noteCards.mapNotNull { memory[it.id] }.filter { !it.isNew }
            if (states.isNotEmpty()) started.merge(note.kind, 1, Int::plus)
            if (states.any { it.phase == MemoryPhase.REVIEW && (it.stability ?: 0.0) >= 21.0 }) {
                wellKnown.merge(note.kind, 1, Int::plus)
            }
        }

        val since30 = now - 30.days
        val recent = reviews.filter { it.reviewedAt >= since30 }
        val recentReviewPhase = recent.filter { it.stateBefore.phase == MemoryPhase.REVIEW }
        val recall = if (recentReviewPhase.size >= 10) {
            recentReviewPhase.count { it.rating != Rating.AGAIN }.toDouble() / recentReviewPhase.size
        } else {
            null
        }

        val retrievabilities = memory.values.filter { !it.isNew }.mapNotNull { scheduler.retrievability(it, now) }
        val startOfToday = dayClock.startOf(dayClock.studyDayOf(now))

        val difficult = recent.filter { it.rating == Rating.AGAIN }
            .groupingBy { it.cardId }.eachCount()
            .mapNotNull { (cardId, n) -> noteOfCard[cardId]?.let { it to n } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
            .entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }

        return ProgressStats(
            notesByKind = notes.groupingBy { it.kind }.eachCount(),
            totalCards = cards.size,
            cardsNotStarted = cards.count { !it.suspended && (memory[it.id]?.isNew ?: true) },
            suspendedCards = cards.count { it.suspended },
            itemsStarted = started,
            itemsWellKnown = wellKnown,
            totalReviews = reviews.size,
            daysStudied = reviews.map { dayClock.studyDayOf(it.reviewedAt) }.distinct().size,
            totalStudyMinutes = (reviews.sumOf { it.durationMillis ?: 0L } / 60_000L).toInt(),
            reviewsLast30Days = recent.size,
            actualRecallLast30Days = recall,
            reviewedToday = reviews.count { it.reviewedAt >= startOfToday },
            estimatedRetentionNow = retrievabilities.takeIf { it.isNotEmpty() }?.average(),
            cardsLikelyFading = retrievabilities.count { it < 0.8 },
            dueByFsrsToday = today.accounting.dueByFsrs,
            plannedToday = today.accounting.plannedNow,
            deferredByWorkloadToday = today.accounting.deferredByWorkload,
            difficultNotes = difficult,
        )
    }
}
