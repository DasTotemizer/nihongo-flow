package app.nihongoflow.core.anki

/** Which collection file a package carried. */
enum class AnkiFormat {
    /** `collection.anki2`: Anki 2.0 and early 2.1. */
    ANKI2,

    /** `collection.anki21`: Anki 2.1 "legacy compatible" exports. */
    ANKI21,

    /** `collection.anki21b`: current Anki (2.1.50 and later), zstd-compressed. */
    ANKI21B,
}

data class AnkiTemplate(val ord: Int, val name: String, val front: String, val back: String)

data class AnkiNoteType(
    val id: Long,
    val name: String,
    val isCloze: Boolean,
    /** Field names in order. A note's field values line up with these. */
    val fields: List<String>,
    val templates: List<AnkiTemplate>,
)

/** A deck. [name] uses Anki's `Parent::Child` form. */
data class AnkiDeck(val id: Long, val name: String, val filtered: Boolean)

data class AnkiNote(
    val id: Long,
    val guid: String,
    val noteTypeId: Long,
    val tags: List<String>,
    /** Raw field values (HTML) in note type order. */
    val fields: List<String>,
)

/**
 * A card as Anki stored it.
 *
 * @property deckId the card's home deck (its original deck if it sits in a filtered deck).
 * @property type 0 new, 1 learning, 2 review, 3 relearning.
 * @property queue -1 suspended, -2 or -3 buried, otherwise the queue.
 * @property due for new cards, the position in the new-card order.
 */
data class AnkiCard(
    val id: Long,
    val noteId: Long,
    val deckId: Long,
    val ord: Int,
    val type: Int,
    val queue: Int,
    val due: Long,
) {
    val suspended: Boolean get() = queue == -1
}

/**
 * One line of Anki's review log.
 *
 * @property id the review time in epoch milliseconds.
 * @property ease 1 Again, 2 Hard, 3 Good, 4 Easy; 0 for manual changes.
 * @property type 0 learn, 1 review, 2 relearn, 3 filtered, 4 manual, 5 rescheduled.
 */
data class AnkiReview(
    val id: Long,
    val cardId: Long,
    val ease: Int,
    val interval: Int,
    val type: Int,
    val takenMillis: Long,
)

/** A media file in the package: its real name and where it sits in the zip. */
data class AnkiMediaEntry(val name: String, val zipEntry: String, val zstdCompressed: Boolean)

data class AnkiCollection(
    val format: AnkiFormat,
    val noteTypes: List<AnkiNoteType>,
    val decks: List<AnkiDeck>,
    val notes: List<AnkiNote>,
    val cards: List<AnkiCard>,
    val reviews: List<AnkiReview>,
    val media: List<AnkiMediaEntry>,
) {
    val noteTypesById: Map<Long, AnkiNoteType> by lazy { noteTypes.associateBy { it.id } }
    val decksById: Map<Long, AnkiDeck> by lazy { decks.associateBy { it.id } }
    val cardsByNote: Map<Long, List<AnkiCard>> by lazy { cards.groupBy { it.noteId } }
    val reviewsByCard: Map<Long, List<AnkiReview>> by lazy { reviews.groupBy { it.cardId }.mapValues { (_, r) -> r.sortedBy { it.id } } }
}

/** Thrown for files that are not a readable Anki package. The message is shown to the learner. */
class AnkiImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
