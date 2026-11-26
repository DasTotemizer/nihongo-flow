package app.nihongoflow.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.study.DayCount
import app.nihongoflow.ui.common.rememberStudyData
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(modifier: Modifier = Modifier) {
    val stats = rememberStudyData { stats() }
    if (stats == null) {
        Column(modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
        return
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Progress", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Today", "${stats.reviewsToday}", "answers", Modifier.weight(1f))
            Tile("New today", "${stats.newCardsToday}", "cards", Modifier.weight(1f))
            Tile("Time", "${stats.minutesToday}", "minutes", Modifier.weight(1f))
        }
        Section("Last 7 days") { Bars(stats.last7Days) }
        Section("Your collection") {
            Line("Cards", stats.totalCards)
            Line("Not started yet", stats.newCards)
            Line("Learning", stats.learningCards)
            Line("Getting familiar (under 3 weeks)", stats.youngCards)
            Line("Well known (3 weeks or more)", stats.matureCards)
        }
        Section("Coming up") {
            Line("To review today", stats.dueToday)
            Bars(stats.forecast)
        }
        Section("Recall") {
            val r = stats.retentionLast30
            Text(
                if (r == null) "After a few more reviews, this shows how often you remembered a card."
                else "You remembered ${(r * 100).roundToInt()}% of reviewed cards over the last 30 days.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Studied on ${stats.studyDaysLast30} of the last 30 days.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(unit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("$value", style = MaterialTheme.typography.bodyMedium)
    }
}

/** A small bar chart of counts per day, labelled with weekday initials. */
@Composable
private fun Bars(days: List<DayCount>) {
    val max = (days.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(96.dp).semantics {
            contentDescription = days.joinToString { "${it.day.dayOfWeek.name.lowercase()}: ${it.count}" }
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { d ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text("${d.count}", style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((56 * d.count / max).coerceAtLeast(2).dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                )
                Text(d.day.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
