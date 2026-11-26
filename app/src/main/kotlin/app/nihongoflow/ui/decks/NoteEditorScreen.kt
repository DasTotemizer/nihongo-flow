package app.nihongoflow.ui.decks

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.Note
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.SaveNoteResult
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.components.JapaneseLocale
import app.nihongoflow.ui.components.MutedText
import kotlinx.coroutines.launch

private data class FieldSpec(val key: String, val label: String, val japanese: Boolean = false, val multiline: Boolean = false)

private fun specsFor(kind: NoteKind): List<FieldSpec> = when (kind) {
    NoteKind.VOCABULARY -> listOf(
        FieldSpec(Fields.WORD, "Word (Japanese)*", japanese = true),
        FieldSpec(Fields.READING, "Reading (kana)", japanese = true),
        FieldSpec(Fields.MEANING, "Meaning (English)*"),
        FieldSpec(Fields.SENTENCE, "Example sentence", japanese = true, multiline = true),
        FieldSpec(Fields.SENTENCE_MEANING, "Sentence translation", multiline = true),
        FieldSpec(Fields.PITCH, "Pitch accent", japanese = true),
        FieldSpec(Fields.NOTES, "Notes", multiline = true),
    )
    NoteKind.KANJI -> listOf(
        FieldSpec(Fields.KANJI, "Kanji*", japanese = true),
        FieldSpec(Fields.MEANING, "Meanings*"),
        FieldSpec(Fields.ONYOMI, "On'yomi (katakana)", japanese = true),
        FieldSpec(Fields.KUNYOMI, "Kun'yomi (hiragana)", japanese = true),
        FieldSpec(Fields.NOTES, "Notes", multiline = true),
    )
    NoteKind.GRAMMAR -> listOf(
        FieldSpec(Fields.PATTERN, "Pattern*", japanese = true),
        FieldSpec(Fields.MEANING, "Meaning*"),
        FieldSpec(Fields.EXPLANATION, "Explanation", multiline = true),
        FieldSpec(Fields.EXAMPLE, "Example sentence", japanese = true, multiline = true),
        FieldSpec(Fields.EXAMPLE_MEANING, "Example translation", multiline = true),
    )
    NoteKind.BASIC, NoteKind.BASIC_AND_REVERSED -> listOf(
        FieldSpec(Fields.FRONT, "Front*", multiline = true),
        FieldSpec(Fields.BACK, "Back*", multiline = true),
        FieldSpec(Fields.NOTES, "Notes", multiline = true),
    )
    NoteKind.CLOZE -> listOf(
        FieldSpec(Fields.TEXT, "Sentence*  e.g. 私は寿司を{{c1::食べる}}。", japanese = true, multiline = true),
        FieldSpec(Fields.EXTRA, "Translation or extra notes", multiline = true),
    )
}

private fun kindLabel(kind: NoteKind) = when (kind) {
    NoteKind.VOCABULARY -> "Vocabulary"
    NoteKind.KANJI -> "Kanji"
    NoteKind.GRAMMAR -> "Grammar"
    NoteKind.BASIC -> "Question & answer"
    NoteKind.BASIC_AND_REVERSED -> "Both directions"
    NoteKind.CLOZE -> "Cloze"
}

@Composable
fun NoteEditorScreen(study: StudyService, deckId: String, noteId: String?, nav: Navigator, modifier: Modifier = Modifier) {
    var kind by remember { mutableStateOf(NoteKind.VOCABULARY) }
    val values = remember { mutableStateMapOf<String, String>() }
    var tags by remember { mutableStateOf("") }
    var existing by remember { mutableStateOf<Note?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var duplicates by remember { mutableStateOf<List<Note>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(noteId) {
        if (noteId != null) {
            study.note(noteId)?.let { n ->
                existing = n
                kind = n.kind
                values.clear()
                values.putAll(n.fields)
                tags = n.tags.sorted().joinToString(", ")
            }
        }
    }

    fun save(allowDuplicate: Boolean) {
        scope.launch {
            val result = study.saveNote(
                deckId = existing?.deckId ?: deckId,
                kind = kind,
                fields = specsFor(kind).associate { it.key to values[it.key].orEmpty() },
                tags = tags.split(",").toSet(),
                existingId = existing?.id,
                allowDuplicate = allowDuplicate,
            )
            when (result) {
                is SaveNoteResult.Saved -> nav.back()
                is SaveNoteResult.Invalid -> message = result.reason
                is SaveNoteResult.PossibleDuplicate -> duplicates = result.existing
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = nav.back) { Text("‹ Back") }
        Text(if (existing == null) "New card" else "Edit card", style = MaterialTheme.typography.headlineMedium)
        if (existing == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteKind.entries.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k; message = null }, label = { Text(kindLabel(k)) })
                }
            }
        } else {
            MutedText("${kindLabel(kind)} · editing keeps your review history")
        }
        if (kind == NoteKind.CLOZE) {
            MutedText("Wrap the part to hide in {{c1::…}}. Use c2, c3 for more cards from the same sentence. Add a hint with {{c1::answer::hint}}.")
        }
        specsFor(kind).forEach { spec ->
            OutlinedTextField(
                value = values[spec.key].orEmpty(),
                onValueChange = { values[spec.key] = it; message = null },
                label = { Text(spec.label) },
                singleLine = !spec.multiline,
                minLines = if (spec.multiline) 2 else 1,
                textStyle = if (spec.japanese) MaterialTheme.typography.bodyLarge.copy(localeList = JapaneseLocale)
                else MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it },
            label = { Text("Tags (comma separated)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        if (duplicates.isNotEmpty()) {
            Text("You already have a card for this.", style = MaterialTheme.typography.titleMedium)
            duplicates.take(3).forEach { MutedText("• ${primaryText(it)}  ${secondaryText(it)}") }
            RowSpacer {
                OutlinedButton(onClick = { duplicates = emptyList() }) { Text("Keep editing") }
                Button(onClick = { duplicates = emptyList(); save(allowDuplicate = true) }) { Text("Save anyway") }
            }
        } else {
            RowSpacer {
                Button(onClick = { save(allowDuplicate = false) }) { Text("Save") }
                if (existing != null) OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
    }

    if (confirmDelete) {
        val note = existing
        ConfirmDialog(
            title = "Delete this card?",
            message = "This removes the note, its cards and their review history from this device. It can't be undone.",
            confirm = "Delete",
            onDismiss = { confirmDelete = false },
        ) {
            confirmDelete = false
            if (note != null) scope.launch { study.deleteNote(note.id); nav.back() }
        }
    }
}
