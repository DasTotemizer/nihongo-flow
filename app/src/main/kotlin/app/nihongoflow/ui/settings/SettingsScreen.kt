package app.nihongoflow.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayPeriod
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.ui.LocalStudyService
import app.nihongoflow.ui.common.DateFormat
import app.nihongoflow.ui.common.rememberStudyData
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        HolidaySection()
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Nihongo Flow schedules reviews with FSRS (java-fsrs by open-spaced-repetition, MIT licence). " +
                        "Missed days are spread out gently instead of piling up. Your cards stay on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Session length, new cards per day and other study settings arrive in the next stage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val MODES = listOf(
    HolidayMode.FULL to "Full holiday",
    HolidayMode.CASUAL to "Casual",
    HolidayMode.INDEFINITE_PAUSE to "Pause",
)

private fun describe(mode: HolidayMode) = when (mode) {
    HolidayMode.FULL -> "No new cards, no targets, nothing expected. You can still open the app for a short session."
    HolidayMode.CASUAL -> "Optional 2 to 5 minute sessions with no targets and no new cards."
    HolidayMode.INDEFINITE_PAUSE -> "An open-ended break until you choose to come back."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HolidaySection() {
    val study = LocalStudyService.current
    val scope = rememberCoroutineScope()
    val status = rememberStudyData { holidayStatus() }
    val holidays = rememberStudyData { holidays() }
    var mode by rememberSaveable { mutableStateOf(HolidayMode.FULL) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var startsIn by rememberSaveable { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Holiday Mode", style = MaterialTheme.typography.titleMedium)
            when (val s = status) {
                is HolidayStatus.Active -> {
                    Text(activeText(s.period), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { scope.launch { study.endHolidayNow(); message = "Welcome back. Today is a normal study day." } }) {
                        Text("I'm back, end it now")
                    }
                }
                else -> {
                    Text("Taking a break? Plan it here and the app will expect nothing while you're away.", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MODES.forEach { (m, label) -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label) }) }
                    }
                    Text(describe(mode), style = MaterialTheme.typography.bodySmall)
                    if (mode != HolidayMode.INDEFINITE_PAUSE) {
                        Text("How long", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(3, 7, 14, 30).forEach { d ->
                                FilterChip(selected = days == d, onClick = { days = d }, label = { Text("$d days") })
                            }
                        }
                    }
                    Text("Starting", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Today", 1 to "Tomorrow", 7 to "In a week").forEach { (d, label) ->
                            FilterChip(selected = startsIn == d, onClick = { startsIn = d }, label = { Text(label) })
                        }
                    }
                    Button(onClick = {
                        scope.launch {
                            val start = study.studyDay().plus(startsIn, DateTimeUnit.DAY)
                            val end = start.plus(days - 1, DateTimeUnit.DAY)
                            message = study.scheduleHoliday(mode, start, end)
                                .fold({ "Holiday saved. Enjoy your break." }, { it.message })
                        }
                    }) { Text(if (startsIn == 0) "Start holiday" else "Schedule holiday") }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }

            val today = study.studyDay()
            val upcoming = holidays.orEmpty().filter { it.startsOn > today }
            if (upcoming.isNotEmpty()) {
                Text("Planned", style = MaterialTheme.typography.labelLarge)
                upcoming.forEach { h ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${MODES.first { it.first == h.mode }.second}: ${DateFormat.short(h.startsOn)}" + (h.endsOn?.let { " to ${DateFormat.short(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { scope.launch { study.cancelUpcomingHoliday(h.id) } }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

private fun activeText(p: HolidayPeriod): String {
    val kind = MODES.first { it.first == p.mode }.second
    val end = p.endsOn
    return if (end == null) "$kind is on. Your learning will be here when you return."
    else "$kind is on until ${DateFormat.short(end)}, inclusive. Your learning will be here when you return."
}
