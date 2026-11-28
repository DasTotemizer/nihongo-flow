package app.nihongoflow.ui.decks

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.study.Deck
import app.nihongoflow.core.study.StarterDeck
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.Overlay
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.components.SectionCard
import kotlinx.coroutines.launch

private data class DeckRow(val deck: Deck, val cards: Int)

@Composable
fun DecksScreen(study: StudyService, nav: Navigator, refresh: Int, modifier: Modifier = Modifier) {
    var reload by remember { mutableIntStateOf(0) }
    val decks by produceState(emptyList<DeckRow>(), refresh, reload) {
        value = study.decks().map { DeckRow(it, study.cardCount(it.id)) }
    }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) nav.open(Overlay.Import(uri.toString()))
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("My Decks", style = MaterialTheme.typography.headlineMedium)
        if (decks.isEmpty()) MutedText("No decks yet. Add one from the Deck Library below, or make your own.")
        decks.forEach { row ->
            SectionCard(Modifier.clickable { nav.open(Overlay.DeckDetail(row.deck.id)) }) {
                Text(row.deck.name, style = MaterialTheme.typography.titleMedium)
                MutedText("${row.cards} cards · ${row.deck.source.name}")
            }
        }
        RowSpacer {
            Button(onClick = { creating = true }) { Text("New deck") }
            OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Import a file") }
        }
        MutedText("Import Anki decks (.apkg, .colpkg) or word lists (.csv, .txt).")

        Text("Deck Library", style = MaterialTheme.typography.titleLarge)

        SectionCard {
            Text("Kaishi 1.5k", style = MaterialTheme.typography.titleMedium)
            Text("Recommended starter vocabulary: about 1,500 common words with example sentences, audio and pitch accent.")
            MutedText("By 栗 (donkuri), Tyogin and contributors. Licence: not stated by the authors, so Nihongo Flow does not host or bundle it. You download it from the official page.")
            MutedText("Beginner · about 1,500 notes · audio and images included in the official download")
            OutlinedButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KAISHI_RELEASES)))
            }) { Text("Open the official download page") }
            MutedText("After downloading, tap \"Import a file\" above and pick the .apkg file.")
        }

        val starterInstalled = decks.any { it.deck.source == StarterDeck.source }
        SectionCard {
            Text("First steps", style = MaterialTheme.typography.titleMedium)
            Text("Twenty everyday words and three short cloze sentences, written for Nihongo Flow, to try the app with.")
            MutedText("By Nihongo Flow · free to use · ${StarterDeck.noteCount} notes · no audio yet")
            if (starterInstalled) {
                MutedText("Installed")
            } else {
                Button(onClick = { scope.launch { StarterDeck.install(study); reload++ } }) { Text("Install") }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New deck",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                scope.launch {
                    val deck = study.createDeck(name)
                    nav.open(Overlay.DeckDetail(deck.id))
                }
            },
        )
    }
}

const val KAISHI_RELEASES = "https://github.com/donkuri/Kaishi/releases/"

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RowSpacer(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}
