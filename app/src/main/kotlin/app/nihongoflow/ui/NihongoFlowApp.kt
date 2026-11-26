package app.nihongoflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.core.workload.SessionSize
import app.nihongoflow.ui.decks.DecksTab
import app.nihongoflow.ui.home.HomeScreen
import app.nihongoflow.ui.progress.ProgressScreen
import app.nihongoflow.ui.review.ReviewScreen
import app.nihongoflow.ui.settings.SettingsScreen

val LocalStudyService = staticCompositionLocalOf<StudyService> { error("No StudyService provided") }

/** The five main navigation areas from the spec. */
enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    DECKS("My Decks", Icons.AutoMirrored.Filled.List),
    REVIEW("Review", Icons.Filled.PlayArrow),
    PROGRESS("Progress", Icons.Filled.Star),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun NihongoFlowApp() {
    var current by rememberSaveable { mutableStateOf(Destination.HOME) }
    var sessionSize by rememberSaveable { mutableStateOf(SessionSize.SUGGESTED) }
    BackHandler(enabled = current != Destination.HOME) { current = Destination.HOME }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = current == d,
                        onClick = { current = d },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (current) {
            Destination.HOME -> HomeScreen(
                modifier = inner,
                sessionSize = sessionSize,
                onSessionSizeChange = { sessionSize = it },
                onNavigate = { current = it },
            )
            Destination.DECKS -> DecksTab(modifier = inner)
            Destination.REVIEW -> ReviewScreen(
                modifier = inner,
                sessionSize = sessionSize,
                onDone = { current = Destination.HOME },
                onOpenDecks = { current = Destination.DECKS },
            )
            Destination.PROGRESS -> ProgressScreen(modifier = inner)
            Destination.SETTINGS -> SettingsScreen(modifier = inner)
        }
    }
}
