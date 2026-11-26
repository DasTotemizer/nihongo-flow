package app.nihongoflow.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.study.CardFace
import app.nihongoflow.core.study.ReviewSession
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.core.workload.SessionPlan
import app.nihongoflow.core.workload.SessionSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

sealed interface ReviewUi {
    data object Loading : ReviewUi

    /** No session running: shows what a session would contain. */
    data class Ready(val plan: SessionPlan) : ReviewUi

    data class Showing(
        val cardId: CardId,
        val face: CardFace,
        val revealed: Boolean,
        /** Next review time for each answer, shown on the buttons once revealed. */
        val previews: Map<Rating, Instant>,
        val now: Instant,
        val answered: Int,
        val remaining: Int,
        /** Forgot and Remembered only, instead of Again, Hard, Good and Easy. */
        val twoButtons: Boolean = true,
    ) : ReviewUi

    data class Finished(val answered: Int) : ReviewUi
}

/** Holds the running session, so it survives switching tabs. No learning logic lives here. */
class ReviewViewModel(private val study: StudyService) : ViewModel() {

    private val _ui = MutableStateFlow<ReviewUi>(ReviewUi.Loading)
    val ui: StateFlow<ReviewUi> = _ui.asStateFlow()

    private var session: ReviewSession? = null
    private var shownAt: Long = 0L

    val inSession: Boolean get() = session != null

    /** Reloads the session preview when no session is running. */
    fun refresh() {
        if (session != null) return
        viewModelScope.launch { _ui.value = ReviewUi.Ready(study.plan()) }
    }

    fun start(size: SessionSize = SessionSize.SUGGESTED) {
        viewModelScope.launch {
            val plan = study.plan(size)
            if (plan.queue.isEmpty()) {
                _ui.value = ReviewUi.Ready(plan)
                return@launch
            }
            session = study.startSession(plan)
            showNext()
        }
    }

    fun reveal() {
        val s = _ui.value as? ReviewUi.Showing ?: return
        if (!s.revealed) _ui.value = s.copy(revealed = true)
    }

    fun answer(rating: Rating) {
        val s = _ui.value as? ReviewUi.Showing ?: return
        if (!s.revealed) return
        val current = session ?: return
        _ui.value = ReviewUi.Loading // prevents double answers while saving
        viewModelScope.launch {
            val duration = System.currentTimeMillis() - shownAt
            val state = study.answer(s.cardId, rating, duration)
            current.onAnswered(s.cardId, state, Clock.System.now())
            showNext()
        }
    }

    /** Stops here. Everything answered is saved; nothing else changes. */
    fun finishEarly() {
        val answered = session?.answered ?: 0
        session = null
        _ui.value = ReviewUi.Finished(answered)
    }

    fun close() {
        session = null
        refresh()
    }

    private suspend fun showNext() {
        val current = session ?: return
        val now = Clock.System.now()
        val next = current.next(now)
        if (next == null) {
            session = null
            _ui.value = ReviewUi.Finished(current.answered)
            return
        }
        val face = study.cardFace(next)
        if (face == null) { // the card was deleted meanwhile
            showNext()
            return
        }
        shownAt = System.currentTimeMillis()
        _ui.value = ReviewUi.Showing(
            cardId = next,
            face = face,
            revealed = false,
            previews = study.preview(next),
            now = now,
            answered = current.answered,
            remaining = current.remaining + 1,
            twoButtons = study.settings().twoButtonReview,
        )
    }

    companion object {
        fun factory(study: StudyService): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReviewViewModel(study) }
        }
    }
}
