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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.viewmodel.compose.viewModel
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.decks.DeckDetailScreen
import app.nihongoflow.ui.decks.DecksScreen
import app.nihongoflow.ui.decks.NoteEditorScreen
import app.nihongoflow.ui.home.HomeScreen
import app.nihongoflow.ui.progress.ProgressScreen
import app.nihongoflow.ui.review.ReviewScreen
import app.nihongoflow.ui.review.ReviewViewModel
import app.nihongoflow.ui.settings.HolidayScreen
import app.nihongoflow.ui.settings.SettingsScreen

/** The five main navigation areas from the spec. */
enum class Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    DECKS("My Decks", Icons.AutoMirrored.Filled.List),
    REVIEW("Review", Icons.Filled.PlayArrow),
    PROGRESS("Progress", Icons.Filled.Star),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Screens shown on top of a tab. */
sealed interface Overlay {
    data class DeckDetail(val deckId: String) : Overlay
    data class EditNote(val deckId: String, val noteId: String?) : Overlay
    data object Holiday : Overlay
}

/** Navigation actions available to every screen. */
class Navigator(
    val goTo: (Destination) -> Unit,
    val open: (Overlay) -> Unit,
    val back: () -> Unit,
)

@Composable
fun NihongoFlowApp(study: StudyService) {
    var current by rememberSaveable { mutableStateOf(Destination.HOME) }
    var stack by remember { mutableStateOf(listOf<Overlay>()) }
    // Bumped whenever data may have changed, so screens reload.
    var refresh by remember { mutableIntStateOf(0) }

    val review: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(study))

    val nav = remember {
        Navigator(
            goTo = { d -> stack = emptyList(); current = d; refresh++ },
            open = { o -> stack = stack + o },
            back = { stack = stack.dropLast(1); refresh++ },
        )
    }

    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }
    BackHandler(enabled = stack.isEmpty() && current != Destination.HOME) { nav.goTo(Destination.HOME) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = current == d && stack.isEmpty(),
                        onClick = { nav.goTo(d) },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (val top = stack.lastOrNull()) {
            is Overlay.DeckDetail -> DeckDetailScreen(study, top.deckId, nav, refresh, modifier)
            is Overlay.EditNote -> NoteEditorScreen(study, top.deckId, top.noteId, nav, modifier)
            Overlay.Holiday -> HolidayScreen(study, nav, modifier)
            null -> when (current) {
                Destination.HOME -> HomeScreen(study, review, nav, refresh, modifier)
                Destination.DECKS -> DecksScreen(study, nav, refresh, modifier)
                Destination.REVIEW -> ReviewScreen(review, nav, modifier)
                Destination.PROGRESS -> ProgressScreen(study, refresh, modifier)
                Destination.SETTINGS -> SettingsScreen(study, nav, modifier)
            }
        }
    }
}
