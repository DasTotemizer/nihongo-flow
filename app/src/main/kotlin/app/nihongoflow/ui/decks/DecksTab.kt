package app.nihongoflow.ui.decks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.study.CardContent
import app.nihongoflow.core.study.DeckId
import app.nihongoflow.core.study.DeckSummary
import app.nihongoflow.ui.LocalStudyService
import app.nihongoflow.ui.common.rememberStudyData
import kotlinx.coroutines.launch

/** My Decks: the deck list, one deck's cards, and the card editor. */
@Composable
fun DecksTab(modifier: Modifier = Modifier) {
    var openDeck by rememberSaveable { mutableStateOf<DeckId?>(null) }
    // NEW_CARD for a new card, otherwise the id of the card being edited.
    var editing by rememberSaveable { mutableStateOf<CardId?>(null) }

    BackHandler(enabled = openDeck != null) {
        if (editing != null) editing = null else openDeck = null
    }
    val deck = openDeck
    val card = editing
    when {
        deck == null -> DeckList(modifier, onOpen = { openDeck = it })
        card != null -> CardEditor(
            modifier = modifier,
            deckId = deck,
            cardId = card.takeIf { it != NEW_CARD },
            onClose = { editing = null },
        )
        else -> DeckDetail(
            modifier = modifier,
            deckId = deck,
            onBack = { openDeck = null },
            onAddCard = { editing = NEW_CARD },
            onEditCard = { editing = it },
        )
    }
}

private const val NEW_CARD: CardId = -1L

@Composable
private fun DeckList(modifier: Modifier, onOpen: (DeckId) -> Unit) {
    val decks = rememberStudyData { deckSummaries() }
    val study = LocalStudyService.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("My Decks", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
            }
            if (decks != null && decks.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "No decks yet. Create one, or add the starter deck to try things out.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        OutlinedButton(onClick = { scope.launch { study.installSampleDeck() } }) {
                            Text("Add the starter deck")
                        }
                        Text(
                            "Anki import and the Kaishi 1.5k guided install arrive in a later stage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(decks.orEmpty(), key = { it.deck.id }) { DeckRow(it, onClick = { onOpen(it.deck.id) }) }
            item { Spacer(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("New deck")
        }
    }

    if (creating) {
        DeckNameDialog(
            title = "New deck",
            initialName = "",
            initialDescription = "",
            onDismiss = { creating = false },
            onSave = { name, description ->
                study.createDeck(name, description).onSuccess { creating = false; onOpen(it) }
                    .exceptionOrNull()?.message
            },
        )
    }
}

@Composable
private fun DeckRow(summary: DeckSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(summary.deck.name, style = MaterialTheme.typography.titleMedium)
            if (summary.deck.description.isNotEmpty()) {
                Text(
                    summary.deck.description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${summary.cardCount} cards · ${summary.newCount} new · ${summary.dueCount} to review today",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** [onSave] returns an error message to show, or null when saved. */
@Composable
private fun DeckNameDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: suspend (String, String) -> String?,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { error = onSave(name, description) } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeckDetail(
    modifier: Modifier,
    deckId: DeckId,
    onBack: () -> Unit,
    onAddCard: () -> Unit,
    onEditCard: (CardId) -> Unit,
) {
    val study = LocalStudyService.current
    val scope = rememberCoroutineScope()
    val deck = rememberStudyData(deckId) { deck(deckId) }
    val cards = rememberStudyData(deckId) { cards(deckId) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack) { Text("‹ My Decks") }
                    Text(deck?.name ?: "", style = MaterialTheme.typography.headlineMedium)
                    if (!deck?.description.isNullOrEmpty()) Text(deck!!.description, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete deck") }
                    }
                    Text("${cards?.size ?: 0} cards", style = MaterialTheme.typography.titleSmall)
                    HorizontalDivider()
                }
            }
            items(cards.orEmpty(), key = { it.id }) { card ->
                Row(
                    Modifier.fillMaxWidth().clickable { onEditCard(card.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(card.content.front, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(0.45f))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        card.content.back,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.55f),
                    )
                    if (card.suspended) {
                        Text("Paused", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                HorizontalDivider()
            }
            item { Spacer(Modifier.padding(40.dp)) }
        }
        ExtendedFloatingActionButton(
            onClick = onAddCard,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("Add card")
        }
    }

    if (renaming && deck != null) {
        DeckNameDialog(
            title = "Rename deck",
            initialName = deck.name,
            initialDescription = deck.description,
            onDismiss = { renaming = false },
            onSave = { name, description ->
                study.renameDeck(deckId, name, description).onSuccess { renaming = false }.exceptionOrNull()?.message
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this deck?") },
            text = { Text("Its ${cards?.size ?: 0} cards and their review history will be removed from this phone. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { study.deleteDeck(deckId); confirmDelete = false; onBack() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun CardEditor(modifier: Modifier, deckId: DeckId, cardId: CardId?, onClose: () -> Unit) {
    val study = LocalStudyService.current
    val scope = rememberCoroutineScope()
    val existing = rememberStudyData(cardId) { cardId?.let { card(it) } }
    var loaded by rememberSaveable(cardId) { mutableStateOf(cardId == null) }
    var front by rememberSaveable(cardId) { mutableStateOf("") }
    var back by rememberSaveable(cardId) { mutableStateOf("") }
    var reading by rememberSaveable(cardId) { mutableStateOf("") }
    var notes by rememberSaveable(cardId) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (!loaded && existing != null) {
        front = existing.content.front
        back = existing.content.back
        reading = existing.content.reading
        notes = existing.content.notes
        loaded = true
    }
    val content = CardContent(front, back, reading, notes)

    fun save(addAnother: Boolean) = scope.launch {
        val result = if (cardId == null) study.addCard(deckId, content).map { } else study.updateCard(cardId, content)
        result.onSuccess {
            if (addAnother) {
                front = ""; back = ""; reading = ""; notes = ""
                message = "Saved. Ready for the next card."
            } else onClose()
        }.onFailure { message = it.message }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onClose) { Text("‹ Back to deck") }
        Text(if (cardId == null) "New card" else "Edit card", style = MaterialTheme.typography.headlineSmall)
        val next = KeyboardOptions(imeAction = ImeAction.Next)
        OutlinedTextField(front, { front = it; message = null }, Modifier.fillMaxWidth(), label = { Text("Front (Japanese)") }, keyboardOptions = next)
        OutlinedTextField(reading, { reading = it }, Modifier.fillMaxWidth(), label = { Text("Reading (optional, kana)") }, keyboardOptions = next)
        OutlinedTextField(back, { back = it; message = null }, Modifier.fillMaxWidth(), label = { Text("Back (meaning)") }, keyboardOptions = next)
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes (optional)") }, minLines = 2)
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save(addAnother = false) }, enabled = content.isValid) { Text("Save") }
            if (cardId == null) {
                OutlinedButton(onClick = { save(addAnother = true) }, enabled = content.isValid) { Text("Save and add another") }
            }
        }
        if (cardId != null && existing != null) {
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pause this card", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "A paused card is left out of sessions until you turn it back on. Its progress is kept.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = existing.suspended,
                    onCheckedChange = { checked -> scope.launch { study.setSuspended(cardId, checked) } },
                )
            }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete card") }
        }
    }

    if (confirmDelete && cardId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this card?") },
            text = { Text("The card and its review history will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { study.deleteCard(cardId); confirmDelete = false; onClose() } }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}
