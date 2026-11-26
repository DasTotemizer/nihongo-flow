package app.nihongoflow.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.components.SectionCard
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

private enum class Length(val label: String, val days: Int?) {
    THREE_DAYS("3 days", 3),
    WEEK("1 week", 7),
    TWO_WEEKS("2 weeks", 14),
    MONTH("1 month", 30),
    OPEN("Until I'm back", null),
}

private data class HolidayData(val status: HolidayStatus, val today: LocalDate)

@Composable
fun HolidayScreen(study: StudyService, nav: Navigator, modifier: Modifier = Modifier) {
    var reload by remember { mutableIntStateOf(0) }
    val data by produceState<HolidayData?>(null, reload) { value = HolidayData(study.holidayStatus(), study.today()) }
    var mode by remember { mutableStateOf(HolidayMode.FULL) }
    var length by remember { mutableStateOf(Length.WEEK) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val d = data ?: return
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = nav.back) { Text("‹ Back") }
        Text("Holiday Mode", style = MaterialTheme.typography.headlineMedium)

        when (val status = d.status) {
            is HolidayStatus.Active -> {
                SectionCard {
                    Text(
                        if (status.period.mode == HolidayMode.CASUAL) "Casual holiday is on." else "Holiday Mode is active.",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("Your learning will be here when you return.")
                    MutedText(status.period.endsOn?.let { "Ends after $it. Normal sessions start again the next day, at an easy pace." }
                        ?: "No end date. Come back whenever you like.")
                }
                Text("Change it", style = MaterialTheme.typography.titleMedium)
                ModeChips(status.period.mode) { newMode ->
                    scope.launch {
                        val end = if (newMode == HolidayMode.INDEFINITE_PAUSE) null else status.period.endsOn ?: d.today.plus(7, DateTimeUnit.DAY)
                        study.changeActiveHoliday(newMode, end).onFailure { error = it.message }
                        reload++
                    }
                }
                Button(onClick = { scope.launch { study.endHolidayNow(); reload++ } }) { Text("I'm back: end holiday now") }
            }
            else -> {
                if (status is HolidayStatus.Upcoming) {
                    SectionCard {
                        Text("Planned holiday", style = MaterialTheme.typography.titleMedium)
                        MutedText("From ${status.period.startsOn}" + (status.period.endsOn?.let { " to $it" } ?: ", open-ended"))
                        OutlinedButton(onClick = { scope.launch { study.cancelHoliday(status.period.id); reload++ } }) { Text("Cancel it") }
                    }
                }
                Text("Take a break", style = MaterialTheme.typography.titleMedium)
                ModeChips(mode) { mode = it }
                MutedText(modeDescription(mode))
                if (mode != HolidayMode.INDEFINITE_PAUSE) {
                    Text("For how long?", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Length.entries.forEach { l ->
                            FilterChip(selected = length == l, onClick = { length = l }, label = { Text(l.label) })
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                Button(onClick = {
                    scope.launch {
                        val end = if (mode == HolidayMode.INDEFINITE_PAUSE) null
                        else length.days?.let { d.today.plus(it - 1, DateTimeUnit.DAY) }
                        val chosenMode = if (mode != HolidayMode.INDEFINITE_PAUSE && end == null) HolidayMode.FULL else mode
                        study.scheduleHoliday(chosenMode, d.today, end)
                            .onSuccess { error = null }
                            .onFailure { error = "That overlaps a holiday you've already planned." }
                        reload++
                    }
                }) { Text("Start from today") }
                MutedText("While you're away, nothing is expected and no reminders are sent. Your memory model keeps real time, and when you return the app eases you back in.")
            }
        }
    }
}

@Composable
private fun ModeChips(selected: HolidayMode, onSelect: (HolidayMode) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HolidayMode.entries.forEach { m ->
            FilterChip(
                selected = selected == m,
                onClick = { onSelect(m) },
                label = {
                    Text(
                        when (m) {
                            HolidayMode.FULL -> "Full holiday"
                            HolidayMode.CASUAL -> "Casual"
                            HolidayMode.INDEFINITE_PAUSE -> "Pause"
                        },
                    )
                },
            )
        }
    }
}

private fun modeDescription(mode: HolidayMode) = when (mode) {
    HolidayMode.FULL -> "No new cards, no reminders, no targets. A complete rest."
    HolidayMode.CASUAL -> "Optional 2 to 5 minute sessions, with no targets and no new cards."
    HolidayMode.INDEFINITE_PAUSE -> "Pause until you choose to come back. Good for unexpected breaks."
}
