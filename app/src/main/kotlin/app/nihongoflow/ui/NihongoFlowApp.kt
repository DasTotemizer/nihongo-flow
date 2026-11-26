package app.nihongoflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.nihongoflow.ui.home.HomeScreen

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
        val modifier = Modifier.padding(padding)
        when (current) {
            Destination.HOME -> HomeScreen(modifier = modifier, onNavigate = { current = it })
            else -> StageNotice(current, modifier)
        }
    }
}

/**
 * Honest notice for areas that arrive in the next build stage, rather than buttons that
 * pretend to work.
 */
@Composable
private fun StageNotice(destination: Destination, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(destination.label, style = MaterialTheme.typography.headlineMedium)
        Text(
            "This area is built in the next stage, together with the on-device database.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
