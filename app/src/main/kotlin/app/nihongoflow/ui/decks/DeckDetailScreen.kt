package app.nihongoflow.ui.decks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.study.Deck
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.Note
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.core.study.Cloze
import app.nihongoflow.core.study.JapaneseText
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.Overlay
import app.nihongoflow.ui.components.JText
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.components.SectionCard
import kotlinx.coroutines.launch

private data class DeckData(val deck: Deck?, val notes: List<Note>)

@Composable
fun DeckDetailScreen(study: StudyService, deckId: String, nav: Navigator, refresh: Int, modifier: Modifier = Modifier) {
    var reload by remember { mutableIntStateOf(0) }
    val data by produceState(DeckData(null, emptyList()), deckId, refresh, reload) {
        value = DeckData(study.deck(deckId), study.notes(deckId))
    }
    var query by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val deck = data.deck
    val q = JapaneseText.normalizeReading(query.trim().lowercase())
    val shown = if (q.isEmpty()) data.notes else data.notes.filter { n ->
        n.fields.values.any { JapaneseText.normalizeReading(it.lowercase()).contains(q) } ||
            n.tags.any { it.lowercase().contains(q) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = nav.back) { Text("‹ My Decks") }
                Text(deck?.name ?: "", style = MaterialTheme.typography.headlineMedium)
                if (!deck?.description.isNullOrBlank()) MutedText(deck!!.description)
                deck?.source?.let { src ->
                    MutedText(listOfNotNull(src.name, src.author?.let { "by $it" }, src.licence).joinToString(" · "))
                }
                RowSpacer {
                    Button(onClick = { nav.open(Overlay.EditNote(deckId, null)) }) { Text("Add a card") }
                    OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
                    OutlinedButton(onClick = { deleting = true }) { Text("Delete") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search this deck") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MutedText("${shown.size} of ${data.notes.size} notes")
            }
        }
        items(shown, key = { it.id }) { note ->
            SectionCard(Modifier.clickable { nav.open(Overlay.EditNote(deckId, note.id)) }) {
                JText(primaryText(note), MaterialTheme.typography.titleMedium)
                val secondary = secondaryText(note)
                if (secondary.isNotBlank()) MutedText(secondary)
            }
        }
        item { Column(Modifier.padding(bottom = 24.dp)) {} }
    }

    if (renaming && deck != null) {
        NameDialog("Rename deck", deck.name, onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.launch { study.renameDeck(deck.id, name, deck.description); reload++ }
        }
    }
    if (deleting && deck != null) {
        ConfirmDialog(
            title = "Delete \"${deck.name}\"?",
            message = "This removes the deck, its ${data.notes.size} notes and all their review history from this device. It can't be undone.",
            confirm = "Delete",
            onDismiss = { deleting = false },
        ) {
            deleting = false
            scope.launch { study.deleteDeck(deck.id); nav.back() }
        }
    }
}

fun primaryText(note: Note): String = when (note.kind) {
    NoteKind.VOCABULARY -> note.field(Fields.WORD)
    NoteKind.KANJI -> note.field(Fields.KANJI)
    NoteKind.GRAMMAR -> note.field(Fields.PATTERN)
    NoteKind.BASIC, NoteKind.BASIC_AND_REVERSED -> note.field(Fields.FRONT)
    NoteKind.CLOZE -> Cloze.renderToString(note.field(Fields.TEXT), activeNumber = -1, revealed = true)
}

fun secondaryText(note: Note): String = when (note.kind) {
    NoteKind.VOCABULARY -> listOf(note.field(Fields.READING), note.field(Fields.MEANING)).filter { it.isNotBlank() }.joinToString(" · ")
    NoteKind.KANJI, NoteKind.GRAMMAR -> note.field(Fields.MEANING)
    NoteKind.BASIC, NoteKind.BASIC_AND_REVERSED -> note.field(Fields.BACK)
    NoteKind.CLOZE -> note.field(Fields.EXTRA)
}
