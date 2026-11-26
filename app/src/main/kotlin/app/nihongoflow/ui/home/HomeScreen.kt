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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.study.Today
import app.nihongoflow.core.workload.SessionSize
import app.nihongoflow.ui.Destination
import app.nihongoflow.ui.LocalStudyService
import app.nihongoflow.ui.common.DateFormat
import app.nihongoflow.ui.common.rememberStudyData
import app.nihongoflow.ui.copy.GreetingCopy
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    sessionSize: SessionSize,
    onSessionSizeChange: (SessionSize) -> Unit,
    onNavigate: (Destination) -> Unit,
) {
    val today = rememberStudyData(sessionSize) { today(sessionSize) }
    if (today == null) {
        Column(modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        return
    }
    HomeContent(today, sessionSize, onSessionSizeChange, modifier, onNavigate)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    today: Today,
    sessionSize: SessionSize,
    onSessionSizeChange: (SessionSize) -> Unit,
    modifier: Modifier,
    onNavigate: (Destination) -> Unit,
) {
    val plan = today.plan
    val study = LocalStudyService.current
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }

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
                val detail = if (today.totalCards == 0) {
                    "Try the starter deck, or create a deck of your own."
                } else {
                    GreetingCopy.detail(plan)
                }
                Text(detail, style = MaterialTheme.typography.bodyLarge)
                val summary = GreetingCopy.sessionSummary(plan)
                if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.titleMedium)
                GreetingCopy.newCardNote(plan.newCardPolicy)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                plan.recoveryProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when {
                    plan.queue.isNotEmpty() -> Button(onClick = { onNavigate(Destination.REVIEW) }) {
                        Text(if (plan.suggested) "Start session" else "Study a little anyway")
                    }
                    today.totalCards == 0 -> Button(
                        enabled = !installing,
                        onClick = {
                            installing = true
                            scope.launch { study.installSampleDeck(); installing = false }
                        },
                    ) { Text("Add the starter deck") }
                    else -> OutlinedButton(onClick = { onNavigate(Destination.DECKS) }) { Text("Add more cards") }
                }
            }
        }

        if (plan.queue.isNotEmpty() || sessionSize != SessionSize.SUGGESTED) {
            Text("Session size today", style = MaterialTheme.typography.titleSmall)
            val options = listOf(
                SessionSize.SMALLER to "Smaller",
                SessionSize.SUGGESTED to "Suggested",
                SessionSize.LARGER to "Larger",
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (size, label) ->
                    SegmentedButton(
                        selected = sessionSize == size,
                        onClick = { onSessionSizeChange(size) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }
        }

        when (val h = today.holiday) {
            is HolidayStatus.Active -> HolidayNote("Holiday Mode is on until ${h.period.endsOn?.let(DateFormat::short) ?: "you return"}.", onNavigate)
            is HolidayStatus.Upcoming -> HolidayNote("A holiday starts on ${DateFormat.short(h.period.startsOn)}.", onNavigate)
            HolidayStatus.None -> Unit
        }

        Text("Quick access", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onNavigate(Destination.SETTINGS) }) { Text("Holiday Mode") }
            OutlinedButton(onClick = { onNavigate(Destination.DECKS) }) { Text("My Decks") }
        }
        OutlinedButton(onClick = { onNavigate(Destination.PROGRESS) }) { Text("Progress") }
    }
}

@Composable
private fun HolidayNote(text: String, onNavigate: (Destination) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 12.dp))
        TextButton(onClick = { onNavigate(Destination.SETTINGS) }) { Text("Change") }
    }
}
