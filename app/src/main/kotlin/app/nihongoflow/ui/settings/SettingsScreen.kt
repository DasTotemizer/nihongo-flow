package app.nihongoflow.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.study.ReviewDirection
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.core.study.StudySettings
import app.nihongoflow.core.workload.RecoveryPacing
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.Overlay
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.components.SectionCard
import app.nihongoflow.ui.components.Stepper
import app.nihongoflow.ui.format.Formats
import kotlinx.coroutines.launch
import kotlinx.datetime.DayOfWeek
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(study: StudyService, nav: Navigator, modifier: Modifier = Modifier) {
    var settings by remember { mutableStateOf<StudySettings?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { settings = study.settings() }

    fun update(change: (StudySettings) -> StudySettings) {
        val current = settings ?: return
        val next = change(current)
        settings = next
        scope.launch { runCatching { study.saveSettings(next) } }
    }

    val s = settings ?: return
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionCard {
            Text("Holiday Mode", style = MaterialTheme.typography.titleMedium)
            MutedText("Take a break, or switch to short relaxed sessions. Nothing is lost.")
            Button(onClick = { nav.open(Overlay.Holiday) }) { Text("Open Holiday Mode") }
        }

        SectionCard {
            Text("Daily sessions", style = MaterialTheme.typography.titleMedium)
            Stepper("Usual session length", s.targetSessionMinutes, 3..60, suffix = " min") { v ->
                update { it.copy(targetSessionMinutes = v, maxSessionMinutes = maxOf(it.maxSessionMinutes, v)) }
            }
            Stepper("Longest session", s.maxSessionMinutes, s.targetSessionMinutes..90, suffix = " min") { v ->
                update { it.copy(maxSessionMinutes = v) }
            }
            Stepper("Most reviews in one session", s.maxReviewsPerSession, 10..300, step = 10) { v ->
                update { it.copy(maxReviewsPerSession = v) }
            }
            Stepper("New cards per day", s.newCardsPerDay, 0..50) { v -> update { it.copy(newCardsPerDay = v) } }
            MutedText("New cards pause by themselves for a few days after a break, then come back.")
        }

        SectionCard {
            Text("Coming back after a break", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecoveryPacing.entries.forEach { p ->
                    FilterChip(
                        selected = s.recoveryPacing == p,
                        onClick = { update { it.copy(recoveryPacing = p) } },
                        label = {
                            Text(
                                when (p) {
                                    RecoveryPacing.GENTLE -> "Gentle"
                                    RecoveryPacing.STEADY -> "Steady"
                                    RecoveryPacing.BRISK -> "Brisk"
                                },
                            )
                        },
                    )
                }
            }
            MutedText(
                when (s.recoveryPacing) {
                    RecoveryPacing.GENTLE -> "Starts with shorter sessions and builds up over four study days."
                    RecoveryPacing.STEADY -> "Starts a little shorter and is back to normal on the second day."
                    RecoveryPacing.BRISK -> "Normal session length straight away. Extra cards are still spread out."
                },
            )
        }

        SectionCard {
            Text("Study days", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    val on = d in s.preferredStudyDays
                    FilterChip(
                        selected = on,
                        onClick = {
                            update {
                                val days = if (on) it.preferredStudyDays - d else it.preferredStudyDays + d
                                it.copy(preferredStudyDays = days.ifEmpty { it.preferredStudyDays })
                            }
                        },
                        label = { Text(d.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
            MutedText("On other days nothing is suggested, but you can always study if you like.")
        }

        SectionCard {
            Text("Answer buttons", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Just two: Forgot or Remembered", modifier = Modifier.weight(1f))
                Switch(
                    checked = s.twoButtonReview,
                    onCheckedChange = { on -> update { it.copy(twoButtonReview = on) } },
                )
            }
            MutedText(
                if (s.twoButtonReview) "Swipe left for Forgot, right for Remembered. Works just as well for scheduling."
                else "Again, Hard, Good and Easy, for finer control. Swipe left for Again, right for Good.",
            )
        }

        SectionCard {
            Text("Review directions", style = MaterialTheme.typography.titleMedium)
            ReviewDirection.entries.forEach { dir ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(directionLabel(dir), modifier = Modifier.weight(1f))
                    Switch(
                        checked = dir in s.enabledDirections,
                        onCheckedChange = { on ->
                            update {
                                val next = if (on) it.enabledDirections + dir else it.enabledDirections - dir
                                it.copy(enabledDirections = next.ifEmpty { it.enabledDirections })
                            }
                        },
                    )
                }
            }
        }

        SectionCard {
            Text("Memory target", style = MaterialTheme.typography.titleMedium)
            Text("Aim to remember ${Formats.percent(s.desiredRetention)} of cards when they come up")
            Slider(
                value = s.desiredRetention.toFloat(),
                onValueChange = { v -> update { it.copy(desiredRetention = (v * 100).roundToInt() / 100.0) } },
                valueRange = 0.80f..0.95f,
                steps = 14,
            )
            MutedText("Higher means more reviews; lower means fewer reviews but more forgetting. 90% suits most people.")
        }

        SectionCard {
            Text("About", style = MaterialTheme.typography.titleMedium)
            MutedText("Nihongo Flow is an unofficial, independent app. It is not made, endorsed or operated by WaniKani, Anki or the Kaishi authors.")
            MutedText("Scheduling uses FSRS through java-fsrs by Open Spaced Repetition (MIT licence).")
            MutedText("Your data stays on this device. Nothing is sent anywhere.")
        }
    }
}

private fun directionLabel(d: ReviewDirection) = when (d) {
    ReviewDirection.JAPANESE_TO_ENGLISH -> "Japanese → English"
    ReviewDirection.ENGLISH_TO_JAPANESE -> "English → Japanese"
    ReviewDirection.KANJI -> "Kanji (meaning and readings)"
    ReviewDirection.GRAMMAR -> "Grammar"
    ReviewDirection.CUSTOM -> "My question & answer cards"
    ReviewDirection.CLOZE -> "Cloze sentences"
}
