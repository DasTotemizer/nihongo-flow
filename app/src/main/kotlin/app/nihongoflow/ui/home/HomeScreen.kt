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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.StudyDayClock
import app.nihongoflow.core.scheduler.FsrsScheduler
import app.nihongoflow.core.workload.SessionPlan
import app.nihongoflow.core.workload.WorkloadManager
import app.nihongoflow.core.workload.WorkloadRequest
import app.nihongoflow.core.workload.WorkloadSettings
import app.nihongoflow.ui.Destination
import app.nihongoflow.ui.copy.GreetingCopy
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

@Composable
fun HomeScreen(modifier: Modifier = Modifier, onNavigate: (Destination) -> Unit) {
    // Until the database lands (next stage) the collection is empty, so this is the real
    // workload manager planning for a brand-new learner.
    val plan = remember {
        val manager = WorkloadManager(WorkloadSettings(), FsrsScheduler())
        manager.plan(
            WorkloadRequest(
                now = Clock.System.now(),
                clock = StudyDayClock(TimeZone.currentSystemDefault()),
                dueCards = emptyList(),
                newCards = emptyList(),
                lastStudyDay = null,
                recovery = null,
                holiday = HolidayStatus.None,
            ),
        )
    }
    HomeContent(plan, modifier, onNavigate)
}

@Composable
private fun HomeContent(plan: SessionPlan, modifier: Modifier, onNavigate: (Destination) -> Unit) {
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
                GreetingCopy.newCardNote(plan.newCardPolicy)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                plan.recoveryProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (plan.queue.isNotEmpty()) {
                    Button(onClick = { onNavigate(Destination.REVIEW) }) { Text("Continue learning") }
                } else {
                    Button(onClick = { onNavigate(Destination.DECKS) }) { Text("Browse the Deck Library") }
                }
            }
        }

        Text("Quick access", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onNavigate(Destination.SETTINGS) }) { Text("Holiday Mode") }
            OutlinedButton(onClick = { onNavigate(Destination.DECKS) }) { Text("My Decks") }
        }
        OutlinedButton(onClick = { onNavigate(Destination.DECKS) }) { Text("Create a card") }
    }
}
