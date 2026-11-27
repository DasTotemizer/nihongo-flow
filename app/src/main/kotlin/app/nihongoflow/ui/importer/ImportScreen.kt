package app.nihongoflow.ui.importer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nihongoflow.core.anki.DuplicatePolicy
import app.nihongoflow.core.anki.NoteTypeMapping
import app.nihongoflow.core.anki.ProgressMode
import app.nihongoflow.core.anki.TargetFields
import app.nihongoflow.core.study.MediaRefs
import app.nihongoflow.CrashLog
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.ui.Destination
import app.nihongoflow.ui.Navigator
import app.nihongoflow.ui.components.MutedText
import app.nihongoflow.ui.components.SectionCard

@Composable
fun ImportScreen(vm: ImportViewModel, nav: Navigator, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = nav.back) { Text("‹ Back") }
        Text("Import", style = MaterialTheme.typography.headlineMedium)
        when (val s = ui) {
            ImportUi.Opening -> Working("Reading the file…")
            ImportUi.Importing -> Working("Importing… This can take a minute for a big deck.")
            is ImportUi.Failed -> {
                Text(s.message)
                val error = s.error
                if (error != null) {
                    val context = LocalContext.current
                    MutedText("If you send me the details, I can see exactly what went wrong with this file.")
                    Button(onClick = { CrashLog.share(context, "Import failed: ${s.message}\n\n" + CrashLog.report(context, error)) }) { Text("Send details") }
                }
                OutlinedButton(onClick = nav.back) { Text("Back") }
            }
            is ImportUi.AnkiReady -> AnkiPreviewContent(s, vm)
            is ImportUi.TextReady -> TextPreviewContent(s, vm)
            is ImportUi.Done -> DoneContent(s, nav)
        }
    }
}

@Composable
private fun Working(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun AnkiPreviewContent(s: ImportUi.AnkiReady, vm: ImportViewModel) {
    val p = s.preview
    SectionCard {
        Text(s.fileName, style = MaterialTheme.typography.titleMedium)
        Text("${p.noteCount} notes, ${p.cardCount} cards" + if (p.deckNames.isNotEmpty()) " in ${p.deckNames.joinToString(", ")}" else "")
        if (p.suspendedCount > 0) MutedText("${p.suspendedCount} cards are suspended in Anki and stay suspended.")
        if (p.mediaCount > 0) MutedText("${p.mediaCount} audio and picture files. They're saved now; playing them comes in a later update.")
    }

    if (p.hasProgress) {
        SectionCard {
            Text("Your Anki progress", style = MaterialTheme.typography.titleMedium)
            MutedText("This file has review history for ${p.cardsWithHistory} cards (${p.reviewCount} answers).")
            Choice(
                selected = s.progress == ProgressMode.KEEP_PROGRESS,
                title = "Keep my progress",
                detail = "Your real Anki answers are replayed through FSRS, so each card keeps its memory. If a lot is due, it's spread over your next sessions.",
            ) { vm.setProgress(ProgressMode.KEEP_PROGRESS) }
            Choice(
                selected = s.progress == ProgressMode.FRESH_START,
                title = "Start fresh",
                detail = "Import the cards as new. A few are introduced each day.",
            ) { vm.setProgress(ProgressMode.FRESH_START) }
        }
    }

    Text("Card types", style = MaterialTheme.typography.titleMedium)
    MutedText("Each Anki note type becomes one kind of Nihongo Flow card. Check the guesses below.")
    p.noteTypes.forEach { t ->
        val mapping = s.mappings.getValue(t.noteType.id)
        SectionCard {
            Text(t.noteType.name, style = MaterialTheme.typography.titleMedium)
            MutedText("${t.noteCount} notes · ${t.cardCount} cards")
            KindChips(selected = if (mapping.skip) null else mapping.kind, allowSkip = true) { vm.setKind(t.noteType.id, it) }
            if (!mapping.skip) {
                FieldMappingEditor(mapping, t.noteType.fields) { vm.setMapping(t.noteType.id, it) }
                SamplePreview(vm.sample(t.noteType.id))
            }
        }
    }

    val c = s.check
    if (c == null) {
        MutedText("Checking for cards you already have…")
    } else {
        SectionCard {
            Text("${c.willImport + c.alreadyImported} notes will be imported", style = MaterialTheme.typography.titleMedium)
            if (c.alreadyImported > 0) MutedText("${c.alreadyImported} were imported before. They're updated in place and keep their history here.")
            if (c.incomplete > 0) MutedText("${c.incomplete} are missing a field their card type needs, so they're left out. You can change the fields above.")
            if (c.duplicates > 0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.duplicates} look like cards you already have. Import them anyway", Modifier.weight(1f))
                    Switch(
                        checked = s.duplicates == DuplicatePolicy.IMPORT_ANYWAY,
                        onCheckedChange = { vm.setAnkiDuplicates(if (it) DuplicatePolicy.IMPORT_ANYWAY else DuplicatePolicy.SKIP) },
                    )
                }
            }
        }
    }
    Button(
        onClick = { vm.import() },
        enabled = c != null && s.mappings.values.any { !it.skip },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Import") }
    MutedText("Nothing is changed in your Anki collection. The file stays on this device.")
}

@Composable
private fun TextPreviewContent(s: ImportUi.TextReady, vm: ImportViewModel) {
    SectionCard {
        Text(s.fileName, style = MaterialTheme.typography.titleMedium)
        Text("${s.rows.size} rows, ${s.columns.size} columns")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("The first row is column names", Modifier.weight(1f))
            Switch(checked = s.firstRowIsHeader, onCheckedChange = { on -> vm.updateText { it.copy(firstRowIsHeader = on) } })
        }
    }
    OutlinedTextField(
        value = s.deckName,
        onValueChange = { v -> vm.updateText { it.copy(deckName = v) } },
        label = { Text("Deck") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SectionCard {
        Text("Card type", style = MaterialTheme.typography.titleMedium)
        KindChips(selected = s.mapping.kind, allowSkip = false) { k -> if (k != null) vm.setTextKind(k) }
        FieldMappingEditor(s.mapping, s.columns) { m -> vm.updateText { it.copy(mapping = m) } }
        SamplePreview(vm.textSample())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Import rows that look like cards you already have", Modifier.weight(1f))
        Switch(
            checked = s.duplicates == DuplicatePolicy.IMPORT_ANYWAY,
            onCheckedChange = { on -> vm.updateText { it.copy(duplicates = if (on) DuplicatePolicy.IMPORT_ANYWAY else DuplicatePolicy.SKIP) } },
        )
    }
    Button(onClick = { vm.import() }, enabled = s.deckName.isNotBlank() && s.rows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Text("Import ${s.rows.size} rows")
    }
    MutedText("Text files have no review history, so these cards start new.")
}

@Composable
private fun DoneContent(s: ImportUi.Done, nav: Navigator) {
    val r = s.result
    SectionCard {
        Text("Import finished", style = MaterialTheme.typography.titleLarge)
        Text("${r.notesAdded} notes added" + if (r.notesUpdated > 0) ", ${r.notesUpdated} updated" else "")
        MutedText("${r.cardsAdded} new cards" + if (r.decks.isNotEmpty()) " in ${r.decks.joinToString(", ") { it.name }}" else "")
        if (s.keptProgress) {
            MutedText("${r.cardsWithProgress} cards kept their memory from ${r.reviewsImported} real Anki answers.")
            if (r.cardsWithoutHistory > 0) MutedText("${r.cardsWithoutHistory} cards were scheduled in Anki but their history wasn't in the file, so they start as new.")
        }
        if (r.duplicatesSkipped > 0) MutedText("${r.duplicatesSkipped} left out because you already have them.")
        if (r.incompleteSkipped > 0) MutedText("${r.incompleteSkipped} left out because a needed field was empty.")
        if (r.notesSkippedByChoice > 0) MutedText("${r.notesSkippedByChoice} left out as you chose.")
        if (r.mediaCopied > 0) MutedText("${r.mediaCopied} audio and picture files saved.")
        if (r.mediaMissing > 0) MutedText("${r.mediaMissing} media files the cards mention weren't in the file.")
    }
    Button(onClick = { nav.goTo(Destination.DECKS) }, modifier = Modifier.fillMaxWidth()) { Text("Go to My Decks") }
}

@Composable
private fun Choice(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            MutedText(detail)
        }
    }
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
private fun KindChips(selected: NoteKind?, allowSkip: Boolean, onSelect: (NoteKind?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NoteKind.entries.forEach { k ->
            FilterChip(selected = selected == k, onClick = { onSelect(k) }, label = { Text(kindLabel(k)) })
        }
        if (allowSkip) FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Don't import") })
    }
}

@Composable
private fun FieldMappingEditor(mapping: NoteTypeMapping, sourceFields: List<String>, onChange: (NoteTypeMapping) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide fields" else "Check fields") }
    if (!open) return
    TargetFields.of(mapping.kind).forEach { target ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(target.label + if (target.required) " *" else "", Modifier.weight(1f))
            SourcePicker(
                current = mapping.fields[target.key]?.let { sourceFields.getOrNull(it) },
                options = sourceFields,
            ) { index ->
                val fields = if (index == null) mapping.fields - target.key else mapping.fields + (target.key to index)
                onChange(mapping.copy(fields = fields))
            }
        }
    }
}

@Composable
private fun SourcePicker(current: String?, options: List<String>, onPick: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(current ?: "Nothing") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Nothing") }, onClick = { expanded = false; onPick(null) })
            options.forEachIndexed { i, name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onPick(i) })
            }
        }
    }
}

@Composable
private fun SamplePreview(fields: Map<String, String>) {
    val shown = fields.filterKeys { !it.startsWith("_") }.mapValues { MediaRefs.withoutRefs(it.value) }.filterValues { it.isNotBlank() }
    if (shown.isEmpty()) return
    MutedText("Example: " + shown.values.take(4).joinToString(" · ") { it.replace('\n', ' ').take(60) })
}
