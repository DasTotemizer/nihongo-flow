package app.nihongoflow.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.study.Card
import app.nihongoflow.core.study.ReviewSession
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.core.workload.CardKind
import app.nihongoflow.core.workload.SessionSize
import app.nihongoflow.ui.common.IntervalFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

sealed interface ReviewUiState {
    data object Loading : ReviewUiState

    /** Nothing to study right now. */
    data object Empty : ReviewUiState

    data class Showing(
        val card: Card,
        val kind: CardKind,
        val revealed: Boolean,
        /** Button label suffix for each answer, such as "10m" or "3d". */
        val intervals: Map<Rating, String>,
        val done: Int,
        val total: Int,
    ) : ReviewUiState

    data class Finished(val answers: Int, val cards: Int) : ReviewUiState
}

/** Drives one sitting. Survives rotation, so a half-finished session is not lost. */
class ReviewViewModel(private val study: StudyService) : ViewModel() {
    private val _state = MutableStateFlow<ReviewUiState>(ReviewUiState.Loading)
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var session: ReviewSession? = null
    private var shownAt = TimeSource.Monotonic.markNow()
    private var busy = false
    private var startJob: Job? = null

    /** Starts a sitting unless one is in progress (for example after rotating the phone). */
    fun startIfIdle(size: SessionSize) {
        if (startJob?.isActive == true) return
        val st = _state.value
        if (session == null || st is ReviewUiState.Finished || st is ReviewUiState.Empty) start(size)
    }

    fun start(size: SessionSize) {
        _state.value = ReviewUiState.Loading
        startJob = viewModelScope.launch {
            val s = study.startSession(size)
            session = s
            if (s.plannedTotal == 0) _state.value = ReviewUiState.Empty else showNext()
        }
    }

    private suspend fun showNext() {
        val s = session ?: return
        val next = s.current(study.currentTime())
        if (next == null) {
            _state.value = ReviewUiState.Finished(s.answered, s.cardsSeen)
            return
        }
        val card = study.card(next.cardId)
        if (card == null) { // deleted meanwhile
            s.skip(next.cardId)
            return showNext()
        }
        val now = study.currentTime()
        val intervals = study.previews(card.id).mapValues { (_, due) -> IntervalFormat.short(due - now) }
        shownAt = TimeSource.Monotonic.markNow()
        _state.value = ReviewUiState.Showing(
            card = card,
            kind = next.kind,
            revealed = false,
            intervals = intervals,
            done = s.cardsSeen,
            total = maxOf(s.plannedTotal, s.cardsSeen + s.plannedRemaining),
        )
    }

    fun reveal() {
        val current = _state.value as? ReviewUiState.Showing ?: return
        _state.value = current.copy(revealed = true)
    }

    fun answer(rating: Rating) {
        val current = _state.value as? ReviewUiState.Showing ?: return
        if (!current.revealed || busy) return
        busy = true
        val millis = shownAt.elapsedNow().inWholeMilliseconds
        viewModelScope.launch {
            try {
                val after = study.answer(current.card.id, rating, millis)
                session?.onAnswered(current.card.id, after, study.currentTime())
                showNext()
            } finally {
                busy = false
            }
        }
    }

    /** Leaves the card for a later session without answering it. Its memory is unchanged. */
    fun skip() {
        val current = _state.value as? ReviewUiState.Showing ?: return
        session?.skip(current.card.id)
        viewModelScope.launch { showNext() }
    }
}
