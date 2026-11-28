package app.nihongoflow.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.study.Cloze
import app.nihongoflow.core.study.FaceLine
import app.nihongoflow.core.workload.SessionSize
import app.nihongoflow.ui.Destination
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.components.JText
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.copy.GreetingCopy
import app.nihongoflow.ui.format.Formats
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.nihongoflow.media.MediaFiles
import app.nihongoflow.media.SoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReviewScreen(vm: ReviewViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Box(modifier.fillMaxSize().padding(20.dp)) {
        when (val s = ui) {
            ReviewUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ReviewUi.Ready -> ReadyContent(s, vm, nav)
            is ReviewUi.Showing -> CardContent(s, vm)
            is ReviewUi.Finished -> FinishedContent(s.answered, vm, nav)
        }
    }
}

@Composable
private fun ReadyContent(s: ReviewUi.Ready, vm: ReviewViewModel, nav: Navigator) {
    var size by remember { mutableStateOf(SessionSize.SUGGESTED) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Review", style = MaterialTheme.typography.headlineMedium)
        if (s.plan.queue.isEmpty()) {
            Text(GreetingCopy.headline(s.plan.greeting), style = MaterialTheme.typography.titleLarge)
            Text(GreetingCopy.detail(s.plan), style = MaterialTheme.typography.bodyLarge)
            if (s.plan.accounting.dueByFsrs == 0) {
                Button(onClick = { nav.goTo(Destination.DECKS) }) { Text("Go to My Decks") }
            } else {
                FilledTonalButton(onClick = { vm.start(SessionSize.LARGER) }) { Text("Do a few more") }
            }
            return@Column
        }
        Text(GreetingCopy.sessionSummary(s.plan), style = MaterialTheme.typography.titleLarge)
        GreetingCopy.newCardNote(s.plan.newCardPolicy)?.let { MutedText(it) }
        Text("Session size", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionSize.entries.forEach { option ->
                FilterChip(
                    selected = size == option,
                    onClick = { size = option },
                    label = {
                        Text(
                            when (option) {
                                SessionSize.SMALLER -> "Shorter"
                                SessionSize.SUGGESTED -> "Suggested"
                                SessionSize.LARGER -> "Longer"
                            },
                        )
                    },
                )
            }
        }
        Button(onClick = { vm.start(size) }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
    }
}

@Composable
private fun CardContent(s: ReviewUi.Showing, vm: ReviewViewModel) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(s.cardId) { runCatching { focus.requestFocus() } }
    val context = LocalContext.current
    val sounds = remember { SoundPlayer(context) }
    DisposableEffect(Unit) { onDispose { sounds.stop() } }
    // The question's audio plays when the card shows, the answer's when it's revealed.
    LaunchedEffect(s.cardId, s.revealed) {
        val toPlay = if (s.revealed) s.face.backSounds else s.face.frontSounds
        if (toPlay.isNotEmpty() || !s.revealed) sounds.play(toPlay)
    }
    val replay = if (s.revealed) s.face.frontSounds + s.face.backSounds else s.face.frontSounds
    val pictures = if (s.revealed) s.face.backImages else s.face.frontImages
    var drag by remember(s.cardId) { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.Enter -> if (!s.revealed) vm.reveal() else vm.answer(Rating.GOOD)
                    Key.One -> vm.answer(Rating.AGAIN)
                    Key.Two -> vm.answer(if (s.twoButtons) Rating.GOOD else Rating.HARD)
                    Key.Three -> if (!s.twoButtons) vm.answer(Rating.GOOD) else return@onKeyEvent false
                    Key.Four -> if (!s.twoButtons) vm.answer(Rating.EASY) else return@onKeyEvent false
                    else -> return@onKeyEvent false
                }
                true
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val total = s.answered + s.remaining
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else s.answered.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable(enabled = !s.revealed) { vm.reveal() }
                .pointerInput(s.cardId, s.revealed) {
                    if (!s.revealed) return@pointerInput
                    // Swipe left for Again, right for Good, once the answer is showing.
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            when {
                                drag < -160f -> vm.answer(Rating.AGAIN)
                                drag > 160f -> vm.answer(Rating.GOOD)
                            }
                            drag = 0f
                        },
                        onHorizontalDrag = { _, amount -> drag += amount },
                    )
                },
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                val lines = if (s.revealed) s.face.back else s.face.front
                lines.forEach { FaceLineView(it) }
                pictures.forEach { CardPicture(it) }
                if (replay.isNotEmpty()) {
                    OutlinedButton(onClick = { sounds.play(replay) }) { Text("▶  Play audio") }
                }
                if (!s.revealed) {
                    Spacer(Modifier.height(8.dp))
                    MutedText("Tap to show the answer")
                }
            }
        }
        if (!s.revealed) {
            Button(onClick = { vm.reveal() }, modifier = Modifier.fillMaxWidth()) { Text("Show answer") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val ratings = if (s.twoButtons) listOf(Rating.AGAIN, Rating.GOOD) else Rating.entries
                ratings.forEach { rating ->
                    val label = when (rating) {
                        Rating.AGAIN -> if (s.twoButtons) "Forgot" else "Again"
                        Rating.HARD -> "Hard"
                        Rating.GOOD -> if (s.twoButtons) "Remembered" else "Good"
                        Rating.EASY -> "Easy"
                    }
                    val next = s.previews[rating]?.let { Formats.interval(s.now, it) } ?: ""
                    val colors = if (rating == Rating.GOOD) ButtonDefaults.buttonColors()
                    else ButtonDefaults.filledTonalButtonColors()
                    Button(
                        onClick = { vm.answer(rating) },
                        colors = colors,
                        modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.TextButtonContentPadding,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            Text(next, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        TextButton(onClick = { vm.finishEarly() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Stop for now")
        }
    }
}

@Composable
private fun CardPicture(name: String) {
    val context = LocalContext.current
    val picture by produceState<ImageBitmap?>(null, name) {
        value = withContext(Dispatchers.IO) { MediaFiles.file(context, name)?.let { MediaFiles.picture(it) }?.asImageBitmap() }
    }
    picture?.let {
        Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun FaceLineView(line: FaceLine) {
    val style = when (line.role) {
        FaceLine.Role.PROMPT -> MaterialTheme.typography.displaySmall.copy(fontSize = if (line.text.length > 12) 28.sp else 40.sp)
        FaceLine.Role.ANSWER -> MaterialTheme.typography.titleLarge
        FaceLine.Role.READING -> MaterialTheme.typography.titleMedium
        FaceLine.Role.DETAIL -> MaterialTheme.typography.bodyLarge
        FaceLine.Role.LABEL -> MaterialTheme.typography.labelLarge
    }
    val segments = line.cloze
    if (segments != null) {
        val primary = MaterialTheme.colorScheme.primary
        val text = buildAnnotatedString {
            segments.forEach { seg ->
                when (seg) {
                    is Cloze.Segment.Plain -> append(seg.text)
                    is Cloze.Segment.Blank -> withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append(seg.text) }
                    is Cloze.Segment.Answer -> withStyle(SpanStyle(color = primary, fontWeight = FontWeight.Bold)) { append(seg.text) }
                }
            }
        }
        Text(
            text,
            style = if (line.japanese) style.copy(localeList = LocaleList("ja-JP")) else style,
            textAlign = TextAlign.Center,
        )
    } else {
        JText(
            line.text,
            style,
            japanese = line.japanese,
            textAlign = TextAlign.Center,
            color = if (line.role == FaceLine.Role.DETAIL || line.role == FaceLine.Role.LABEL) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun FinishedContent(answered: Int, vm: ReviewViewModel, nav: Navigator) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Nice work.", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (answered == 1) "You reviewed 1 card." else "You reviewed $answered cards.",
            style = MaterialTheme.typography.bodyLarge,
        )
        MutedText("Everything you answered is saved. Anything left will be spread over your next sessions.")
        Button(onClick = { vm.close(); nav.goTo(Destination.HOME) }) { Text("Back to Home") }
    }
}
