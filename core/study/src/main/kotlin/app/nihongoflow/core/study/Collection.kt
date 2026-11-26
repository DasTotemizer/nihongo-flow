package app.nihongoflow.core.study

import app.nihongoflow.core.model.CardId
import kotlinx.datetime.Instant

typealias DeckId = Long

data class Deck(
    val id: DeckId,
    val name: String,
    val description: String = "",
    val createdAt: Instant,
)

/**
 * What a card says. Front and back are required; the rest is optional.
 *
 * @property reading kana reading of the front, shown with the answer (for example
 *   「たべる」 for 食べる).
 * @property notes anything else: example sentences, mnemonics, grammar notes.
 */
data class CardContent(
    val front: String,
    val back: String,
    val reading: String = "",
    val notes: String = "",
) {
    /** Trimmed copy, so stored cards never carry stray whitespace. */
    fun normalised(): CardContent = CardContent(front.trim(), back.trim(), reading.trim(), notes.trim())

    /** Problems that stop the card being saved, in a form the screen can show. Empty when valid. */
    fun problems(): List<String> = buildList {
        val n = normalised()
        if (n.front.isEmpty()) add("The front needs some text.")
        if (n.back.isEmpty()) add("The back needs some text.")
        if (n.front.length > MAX_FIELD_LENGTH || n.back.length > MAX_FIELD_LENGTH ||
            n.reading.length > MAX_FIELD_LENGTH || n.notes.length > MAX_NOTES_LENGTH
        ) add("One of the fields is too long.")
    }

    val isValid: Boolean get() = problems().isEmpty()

    companion object {
        const val MAX_FIELD_LENGTH = 2_000
        const val MAX_NOTES_LENGTH = 10_000
    }
}

data class Card(
    val id: CardId,
    val deckId: DeckId,
    val content: CardContent,
    val createdAt: Instant,
    /** Order in which new cards are introduced; lower first. */
    val newPosition: Long,
    /** A suspended card is never scheduled until the learner unsuspends it. */
    val suspended: Boolean = false,
)

/** A deck with the numbers My Decks shows next to it. */
data class DeckSummary(
    val deck: Deck,
    val cardCount: Int,
    val newCount: Int,
    /** Cards FSRS considers due by the end of today's study day. */
    val dueCount: Int,
)

/** Rules for deck names, shared by every screen that edits one. */
object DeckRules {
    const val MAX_NAME_LENGTH = 80

    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "Give the deck a name."
        name.trim().length > MAX_NAME_LENGTH -> "That name is a little long (80 characters at most)."
        else -> null
    }
}
