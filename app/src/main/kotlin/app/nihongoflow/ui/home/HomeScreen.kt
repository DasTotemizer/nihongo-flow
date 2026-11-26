package app.nihongoflow.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.study.HomeState
import app.nihongoflow.core.study.StarterDeck
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.Destination
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.Overlay
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.copy.GreetingCopy
import app.nihongoflow.ui.review.ReviewViewModel
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    study: StudyService,
    review: ReviewViewModel,
    nav: Navigator,
    refresh: Int,
    modifier: Modifier = Modifier,
) {
    var reload by remember { mutableIntStateOf(0) }
    val state by produceState<HomeState?>(null, refresh, reload) { value = study.home() }
    val scope = rememberCoroutineScope()

    val s = state
    if (s == null) {
        CircularProgressIndicator(modifier.padding(32.dp))
        return
    }
    val plan = s.plan

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Nihongo Flow", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(GreetingCopy.headline(plan.greeting), style = MaterialTheme.typography.headlineMedium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(GreetingCopy.detail(plan), style = MaterialTheme.typography.bodyLarge)
                val summary = GreetingCopy.sessionSummary(plan)
                if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.titleMedium)
                GreetingCopy.newCardNote(plan.newCardPolicy)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                plan.recoveryProgress?.let { progress ->
                    LinearProgressIndicator(progress = { progress.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                    val days = progress.estimatedStudyDays
                    Text(
                        when {
                            progress.needsLargerSessions -> "A slightly longer session would help things settle. You can change this in Settings."
                            days == null || days <= 1 -> "Almost back to your usual rhythm."
                            else -> "Back to your usual rhythm in about $days study days."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when {
                    s.cardCount == 0 -> {
                        Button(onClick = {
                            scope.launch {
                                StarterDeck.install(study)
                                reload++
                            }
                        }) { Text("Add the starter deck") }
                        OutlinedButton(onClick = { nav.goTo(Destination.DECKS) }) { Text("Browse the Deck Library") }
                    }
                    plan.queue.isNotEmpty() -> Button(onClick = {
                        review.start()
                        nav.goTo(Destination.REVIEW)
                    }) { Text(if (plan.suggested) "Continue learning" else "Practise a little") }
                }
            }
        }

        HolidayStrip(s.holiday, nav)

        Text("Quick access", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { nav.open(Overlay.Holiday) }) { Text("Holiday Mode") }
            OutlinedButton(onClick = { nav.goTo(Destination.DECKS) }) { Text("My Decks") }
        }
        OutlinedButton(onClick = {
            scope.launch {
                val deckId = study.decks().firstOrNull()?.id ?: study.createDeck("My cards").id
                nav.open(Overlay.EditNote(deckId, null))
            }
        }) { Text("Create a card") }

        if (s.cardCount > 0) {
            MutedText("${s.cardCount} cards in ${s.deckCount} ${if (s.deckCount == 1) "deck" else "decks"}.")
        }
    }
}

@Composable
private fun HolidayStrip(status: HolidayStatus, nav: Navigator) {
    when (status) {
        is HolidayStatus.Active -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (status.period.mode) {
                        HolidayMode.CASUAL -> "Casual holiday is on."
                        else -> "Holiday Mode is active."
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                MutedText(
                    status.period.endsOn?.let { "Until $it. Your learning will be here when you return." }
                        ?: "Until you choose to come back. Your learning will be here when you return.",
                )
                OutlinedButton(onClick = { nav.open(Overlay.Holiday) }) { Text("Change or end") }
            }
        }
        is HolidayStatus.Upcoming -> MutedText("Holiday planned from ${status.period.startsOn}.")
        HolidayStatus.None -> Unit
    }
}
