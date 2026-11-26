package app.nihongoflow.core.study

import kotlinx.datetime.Instant

/** Decides which cards a note should have. */
object CardGenerator {

    /** Template keys (see [Card.templateKey]) the note should have, with their template and cloze number. */
    fun requiredCards(note: Note): List<Pair<CardTemplate, Int>> {
        val all = allCards(note)
        val only = note.field(Fields.ONLY_CARDS).split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (note.kind == NoteKind.CLOZE || only.isEmpty()) return all
        return all.filter { (t, n) -> keyOf(t, n) in only }.ifEmpty { all }
    }

    private fun allCards(note: Note): List<Pair<CardTemplate, Int>> = when (note.kind) {
        NoteKind.VOCABULARY -> buildList {
            add(CardTemplate.RECOGNITION to 0)
            if (note.field(Fields.MEANING).isNotBlank()) add(CardTemplate.PRODUCTION to 0)
        }
        NoteKind.KANJI -> buildList {
            add(CardTemplate.KANJI_MEANING to 0)
            if (note.field(Fields.ONYOMI).isNotBlank() || note.field(Fields.KUNYOMI).isNotBlank()) {
                add(CardTemplate.KANJI_READING to 0)
            }
        }
        NoteKind.GRAMMAR -> listOf(CardTemplate.GRAMMAR to 0)
        NoteKind.BASIC -> listOf(CardTemplate.FRONT_TO_BACK to 0)
        NoteKind.BASIC_AND_REVERSED -> listOf(CardTemplate.FRONT_TO_BACK to 0, CardTemplate.BACK_TO_FRONT to 0)
        NoteKind.CLOZE -> Cloze.numbers(note.field(Fields.TEXT)).map { CardTemplate.CLOZE to it }
    }

    /**
     * Reconciles a note's cards after creation or an edit. Existing cards are kept with their
     * ids (and so their memory and history), missing ones are created, and cards no longer
     * required are suspended rather than deleted. A suspended card that is required again is
     * un-suspended.
     */
    fun reconcile(
        note: Note,
        existing: List<Card>,
        now: Instant,
        nextNewPosition: () -> Long,
        newId: () -> String,
    ): Reconciliation {
        val required = requiredCards(note)
        val requiredKeys = required.map { (t, n) -> keyOf(t, n) }.toSet()
        val byKey = existing.associateBy { it.templateKey }
        val created = required
            .filter { (t, n) -> keyOf(t, n) !in byKey }
            .map { (t, n) ->
                Card(
                    id = newId(),
                    noteId = note.id,
                    deckId = note.deckId,
                    template = t,
                    clozeNumber = n,
                    createdAt = now,
                    newPosition = nextNewPosition(),
                )
            }
        val updated = existing.mapNotNull { card ->
            val shouldSuspend = card.templateKey !in requiredKeys
            val changed = card.copy(suspended = shouldSuspend, deckId = note.deckId)
            if (changed != card) changed else null
        }
        return Reconciliation(created = created, updated = updated)
    }

    data class Reconciliation(val created: List<Card>, val updated: List<Card>)

    private fun keyOf(t: CardTemplate, n: Int) = if (t == CardTemplate.CLOZE) "cloze:$n" else t.name
}
