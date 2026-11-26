package app.nihongoflow.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.workload.CardKind
import app.nihongoflow.core.workload.SessionSize
import app.nihongoflow.ui.LocalStudyService

@Composable
fun ReviewScreen(
    modifier: Modifier = Modifier,
    sessionSize: SessionSize,
    onDone: () -> Unit,
    onOpenDecks: () -> Unit,
) {
    val study = LocalStudyService.current
    val vm: ReviewViewModel = viewModel(factory = viewModelFactory { initializer { ReviewViewModel(study) } })
    LaunchedEffect(Unit) { vm.startIfIdle(sessionSize) }
    val state by vm.state.collectAsStateWithLifecycle()

    when (val s = state) {
        ReviewUiState.Loading -> Column(modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        ReviewUiState.Empty -> Message(
            modifier,
            title = "Nothing to review right now.",
            body = "Everything planned for today is done, or your decks are empty. You can add cards in My Decks.",
            primary = "Back to Home" to onDone,
            secondary = "My Decks" to onOpenDecks,
        )
        is ReviewUiState.Finished -> Message(
            modifier,
            title = "Session complete. Nice work.",
            body = "${s.cards} card${if (s.cards == 1) "" else "s"} studied. You can stop here, or do a few more.",
            primary = "Back to Home" to onDone,
            secondary = "A few more" to { vm.start(SessionSize.LARGER) },
        )
        is ReviewUiState.Showing -> CardView(modifier, s, vm)
    }
}

@Composable
private fun CardView(modifier: Modifier, s: ReviewUiState.Showing, vm: ReviewViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(s.card.id) { runCatching { focus.requestFocus() } }
    var drag by remember(s.card.id) { mutableFloatStateOf(0f) }

    Column(
        modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.Enter -> { if (s.revealed) vm.answer(Rating.GOOD) else vm.reveal(); true }
                    Key.One -> { vm.answer(Rating.AGAIN); true }
                    Key.Two -> { vm.answer(Rating.HARD); true }
                    Key.Three -> { vm.answer(Rating.GOOD); true }
                    Key.Four -> { vm.answer(Rating.EASY); true }
                    else -> false
                }
            }
            .pointerInput(s.card.id, s.revealed) {
                if (!s.revealed) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            drag > SWIPE_PX -> vm.answer(Rating.GOOD)
                            drag < -SWIPE_PX -> vm.answer(Rating.AGAIN)
                        }
                        drag = 0f
                    },
                    onHorizontalDrag = { _, dx -> drag += dx },
                )
            }
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (s.kind) {
                    CardKind.NEW -> "New card"
                    CardKind.LEARNING -> "Learning"
                    CardKind.REVIEW -> "Review"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::skip) { Text("Later") }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clickable(enabled = !s.revealed, onClick = vm::reveal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.padding(16.dp))
            Text(s.card.content.front, fontSize = 40.sp, textAlign = TextAlign.Center, lineHeight = 52.sp)
            if (s.revealed) {
                HorizontalDivider()
                if (s.card.content.reading.isNotEmpty()) {
                    Text(s.card.content.reading, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(s.card.content.back, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (s.card.content.notes.isNotEmpty()) {
                    Text(s.card.content.notes, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
            }
        }

        if (!s.revealed) {
            Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth()) { Text("Show answer") }
        } else {
            Text(
                "Swipe right for Good, left for Again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                RATINGS.forEach { (rating, label) ->
                    val colors = if (rating == Rating.GOOD) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
                    Button(
                        onClick = { vm.answer(rating) },
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label)
                            Text(s.intervals[rating].orEmpty(), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private const val SWIPE_PX = 160f

private val RATINGS = listOf(
    Rating.AGAIN to "Again",
    Rating.HARD to "Hard",
    Rating.GOOD to "Good",
    Rating.EASY to "Easy",
)

@Composable
private fun Message(
    modifier: Modifier,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = primary.second) { Text(primary.first) }
        OutlinedButton(onClick = secondary.second) { Text(secondary.first) }
    }
}
