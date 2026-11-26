package app.nihongoflow.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.ProgressStats
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.components.JText
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.components.SectionCard
import app.nihongoflow.ui.decks.primaryText
import app.nihongoflow.ui.format.Formats

@Composable
fun ProgressScreen(study: StudyService, refresh: Int, modifier: Modifier = Modifier) {
    val stats by produceState<ProgressStats?>(null, refresh) { value = study.stats() }
    var showDetail by remember { mutableStateOf(false) }

    val s = stats
    if (s == null) {
        CircularProgressIndicator(modifier.padding(32.dp))
        return
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Progress", style = MaterialTheme.typography.headlineMedium)

        SectionCard {
            Text("Milestones", style = MaterialTheme.typography.titleMedium)
            Stat("Words started", s.itemsStarted[NoteKind.VOCABULARY] ?: 0)
            Stat("Words you know well (remembered 3+ weeks)", s.itemsWellKnown[NoteKind.VOCABULARY] ?: 0)
            Stat("Kanji started", s.itemsStarted[NoteKind.KANJI] ?: 0)
            Stat("Grammar points started", s.itemsStarted[NoteKind.GRAMMAR] ?: 0)
            Stat("Reviews completed", s.totalReviews)
            Stat("Days you've studied", s.daysStudied)
            Stat("Time studying (minutes)", s.totalStudyMinutes)
            MutedText("These only ever go up. A day off never resets anything.")
        }

        SectionCard {
            Text("What actually happened", style = MaterialTheme.typography.titleMedium)
            Stat("Reviews in the last 30 days", s.reviewsLast30Days)
            Row(Modifier.fillMaxWidth()) {
                Text("Recalled correctly (last 30 days)", modifier = Modifier.weight(1f))
                Text(s.actualRecallLast30Days?.let { Formats.percent(it) } ?: "Not enough reviews yet")
            }
            Stat("Reviewed today", s.reviewedToday)
        }

        SectionCard {
            Text("Estimates from your memory model", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Text("Predicted recall right now", modifier = Modifier.weight(1f))
                Text(s.estimatedRetentionNow?.let { Formats.percent(it) } ?: "—")
            }
            Stat("Cards that may be fading", s.cardsLikelyFading)
            MutedText("Estimates come from FSRS and change with time. They're predictions, not results.")
        }

        if (s.difficultNotes.isNotEmpty()) {
            SectionCard {
                Text("Trickiest recently", style = MaterialTheme.typography.titleMedium)
                s.difficultNotes.take(5).forEach { (note, n) ->
                    Row(Modifier.fillMaxWidth()) {
                        JText(primaryText(note), MaterialTheme.typography.bodyLarge, Modifier.weight(1f))
                        MutedText("$n× Again")
                    }
                }
            }
        }

        TextButton(onClick = { showDetail = !showDetail }) {
            Text(if (showDetail) "Hide the full picture" else "Show the full picture for today")
        }
        if (showDetail) {
            SectionCard {
                Stat("Due today according to FSRS", s.dueByFsrsToday)
                Stat("In today's suggested session", s.plannedToday)
                Stat("Spread to later sessions", s.deferredByWorkloadToday)
                Stat("Reviewed today", s.reviewedToday)
                MutedText("Cards spread to later sessions stay due in FSRS and keep their real history. They'll come up in the next sessions, oldest waits first.")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
    }
}
