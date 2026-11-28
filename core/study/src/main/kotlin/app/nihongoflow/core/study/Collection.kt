package app.nihongoflow.core.study

import app.nihongoflow.core.model.CardId
import kotlinx.datetime.Instant

/** Where a deck or note came from. Kept on everything so attribution is never lost. */
enum class SourceKind { USER, BUILT_IN, ANKI_IMPORT, KAISHI, WANIKANI }

data class ContentSource(
    val kind: SourceKind,
    val name: String,
    val author: String? = null,
    val licence: String? = null,
    val url: String? = null,
) {
    companion object {
        val USER = ContentSource(SourceKind.USER, "My cards")
    }
}

data class Deck(
    val id: String,
    val name: String,
    val description: String = "",
    /** Parent deck for hierarchies such as "Japanese::Vocabulary". Null for a top-level deck. */
    val parentId: String? = null,
    val source: ContentSource = ContentSource.USER,
    val createdAt: Instant,
)

/** The kinds of note a learner can create. Each kind produces one or more cards. */
enum class NoteKind { VOCABULARY, KANJI, GRAMMAR, BASIC, BASIC_AND_REVERSED, CLOZE }

/** Field names used by each note kind. Notes keep any extra fields (for example from Anki) as-is. */
object Fields {
    // Vocabulary
    const val WORD = "word"
    const val READING = "reading"
    const val MEANING = "meaning"
    const val SENTENCE = "sentence"
    const val SENTENCE_READING = "sentenceReading"
    const val SENTENCE_MEANING = "sentenceMeaning"
    const val PITCH = "pitch"
    const val JLPT = "jlpt"

    // Kanji
    const val KANJI = "kanji"
    const val ONYOMI = "onyomi"
    const val KUNYOMI = "kunyomi"

    // Grammar
    const val PATTERN = "pattern"
    const val EXPLANATION = "explanation"
    const val EXAMPLE = "example"
    const val EXAMPLE_MEANING = "exampleMeaning"

    // Basic
    const val FRONT = "front"
    const val BACK = "back"

    // Cloze
    const val TEXT = "text"
    const val EXTRA = "extra"

    // Any kind
    const val NOTES = "notes"

    /** Media references (`[sound:…]`, `[image:…]`), kept for audio and pictures. Not edited in the card editor. */
    const val AUDIO = "audio"
    const val SENTENCE_AUDIO = "sentenceAudio"
    const val IMAGE = "image"

    // Bookkeeping fields, never shown. Editing a note keeps them.

    /** The Anki note's guid, so importing the same deck again updates instead of duplicating. */
    const val ANKI_GUID = "_ankiGuid"

    /**
     * Comma-separated [Card.templateKey]s this note is limited to, for non-cloze notes.
     * Set by imports so a deck keeps the cards it had in Anki (for example recognition only).
     */
    const val ONLY_CARDS = "_onlyCards"
}

data class Note(
    val id: String,
    val deckId: String,
    val kind: NoteKind,
    val fields: Map<String, String>,
    val tags: Set<String> = emptySet(),
    val source: ContentSource = ContentSource.USER,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun field(name: String): String = fields[name].orEmpty()
}

/**
 * Which side-to-side direction a card asks. A note's cards each have their own FSRS memory,
 * as in Anki, so recognising a word and producing it are scheduled independently.
 */
enum class CardTemplate {
    /** Japanese → English. */
    RECOGNITION,

    /** English → Japanese. */
    PRODUCTION,

    /** Kanji → meaning. */
    KANJI_MEANING,

    /** Kanji → readings. */
    KANJI_READING,

    /** Grammar pattern → meaning and usage. */
    GRAMMAR,

    FRONT_TO_BACK,
    BACK_TO_FRONT,

    /** One cloze deletion; the card's [Card.clozeNumber] says which. */
    CLOZE,
}

/** The review directions the learner can switch on and off in Settings. */
enum class ReviewDirection(val templates: Set<CardTemplate>) {
    JAPANESE_TO_ENGLISH(setOf(CardTemplate.RECOGNITION)),
    ENGLISH_TO_JAPANESE(setOf(CardTemplate.PRODUCTION)),
    KANJI(setOf(CardTemplate.KANJI_MEANING, CardTemplate.KANJI_READING)),
    GRAMMAR(setOf(CardTemplate.GRAMMAR)),
    CUSTOM(setOf(CardTemplate.FRONT_TO_BACK, CardTemplate.BACK_TO_FRONT)),
    CLOZE(setOf(CardTemplate.CLOZE)),
    ;

    companion object {
        val DEFAULT_ENABLED: Set<ReviewDirection> = entries.toSet()

        fun enabledTemplates(directions: Set<ReviewDirection>): Set<CardTemplate> =
            directions.flatMapTo(mutableSetOf()) { it.templates }
    }
}

data class Card(
    val id: CardId,
    val noteId: String,
    val deckId: String,
    val template: CardTemplate,
    /** For cloze cards, the cloze number (c1 = 1). Zero otherwise. */
    val clozeNumber: Int = 0,
    val createdAt: Instant,
    /** Order in which new cards are introduced (lower first). */
    val newPosition: Long,
    /**
     * A suspended card is kept with all its history but not shown. Used when an edit removes
     * the cloze or direction a card was for, so history is never deleted by editing.
     */
    val suspended: Boolean = false,
) {
    /** Identifies which card of its note this is; stable across edits. */
    val templateKey: String get() = if (template == CardTemplate.CLOZE) "cloze:$clozeNumber" else template.name
}
