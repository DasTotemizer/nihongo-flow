package app.nihongoflow.core.anki

import app.nihongoflow.core.model.CardId
import app.nihongoflow.core.model.MemoryState
import app.nihongoflow.core.model.ReviewRecord
import app.nihongoflow.core.scheduler.MemoryScheduler
import app.nihongoflow.core.study.Card
import app.nihongoflow.core.study.CardGenerator
import app.nihongoflow.core.study.CollectionRepository
import app.nihongoflow.core.study.ContentSource
import app.nihongoflow.core.study.Deck
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.ImportBatch
import app.nihongoflow.core.study.ImportRepository
import app.nihongoflow.core.study.MediaRefs
import app.nihongoflow.core.study.Note
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.NoteRules
import app.nihongoflow.core.study.SourceKind
import kotlinx.datetime.Instant

/** Whether imported cards start fresh or keep their real review history. */
enum class ProgressMode { FRESH_START, KEEP_PROGRESS }

/** What to do with a note that looks like one you already have. */
enum class DuplicatePolicy { SKIP, IMPORT_ANYWAY }

data class ImportResult(
    val notesAdded: Int,
    /** Notes imported before (same Anki note) that were updated in place, keeping their history. */
    val notesUpdated: Int,
    val duplicatesSkipped: Int,
    /** Notes missing something a card needs, such as a meaning. */
    val incompleteSkipped: Int,
    val notesSkippedByChoice: Int,
    val cardsAdded: Int,
    val cardsWithProgress: Int,
    val reviewsImported: Int,
    /** Cards Anki had scheduled but whose review history wasn't in the file; they start as new. */
    val cardsWithoutHistory: Int,
    val mediaCopied: Int,
    val mediaMissing: Int,
    val decks: List<Deck>,
)

/** One note about to be imported, already converted to Nihongo Flow fields. */
internal data class IncomingNote(
    val sourceId: String?,
    val kind: NoteKind,
    val fields: Map<String, String>,
    val tags: Set<String>,
    val deckName: String,
    val createdAt: Instant?,
    /** Source cards by the Nihongo Flow card key they become. */
    val cards: Map<String, IncomingCard> = emptyMap(),
)

internal data class IncomingCard(val suspended: Boolean, val reviews: List<AnkiReview>, val scheduledInSource: Boolean)

/**
 * Turns converted notes into one [ImportBatch]: finds or creates decks, skips duplicates,
 * updates notes imported before, makes cards and, when asked, replays review history.
 */
internal class ImportWriter(
    private val collection: CollectionRepository,
    private val sink: ImportRepository,
    private val now: Instant,
    private val newId: () -> String,
    private val source: ContentSource,
) {
    suspend fun write(
        incoming: List<IncomingNote>,
        duplicates: DuplicatePolicy,
        progressMode: ProgressMode,
        scheduler: MemoryScheduler?,
        skippedByChoice: Int,
        incomplete: Int,
        copyMedia: ((String) -> Boolean?)?,
    ): ImportResult {
        val existing = collection.allNotes()
        val bySourceId = existing.mapNotNull { n -> n.fields[Fields.ANKI_GUID]?.let { it to n } }.toMap()
        val keys = existing.mapNotNull { n -> NoteRules.duplicateKey(n.kind, n.fields)?.let { "${n.kind}|$it" } }.toMutableSet()

        val decksByName = collection.decks().filter { it.source.kind == SourceKind.ANKI_IMPORT }.associateBy { it.name }.toMutableMap()
        val newDecks = mutableListOf<Deck>()
        fun deckFor(name: String): Deck = decksByName.getOrPut(name) {
            Deck(newId(), name, source = source, createdAt = now).also { newDecks += it }
        }

        var position = collection.maxNewPosition()
        val notes = mutableListOf<Note>()
        val created = mutableListOf<Card>()
        val updated = mutableListOf<Card>()
        val memory = mutableMapOf<CardId, MemoryState>()
        val history = mutableListOf<ReviewRecord>()
        var added = 0
        var updatedNotes = 0
        var dupes = 0
        var withProgress = 0
        var withoutHistory = 0
        val mediaNames = linkedSetOf<String>()

        for (n in incoming) {
            val previous = n.sourceId?.let { bySourceId[it] }
            val key = NoteRules.duplicateKey(n.kind, n.fields)?.let { "${n.kind}|$it" }
            if (previous == null && duplicates == DuplicatePolicy.SKIP && key != null && key in keys) {
                dupes++
                continue
            }
            key?.let { keys += it }
            val fields = buildMap {
                previous?.fields?.let { putAll(it) }
                putAll(n.fields)
                n.sourceId?.let { put(Fields.ANKI_GUID, it) }
                if (n.kind != NoteKind.CLOZE && n.cards.isNotEmpty()) put(Fields.ONLY_CARDS, n.cards.keys.sorted().joinToString(","))
            }
            val note = Note(
                id = previous?.id ?: newId(),
                deckId = previous?.deckId ?: deckFor(n.deckName).id,
                kind = n.kind,
                fields = fields,
                tags = (previous?.tags.orEmpty() + n.tags),
                source = previous?.source ?: source,
                createdAt = previous?.createdAt ?: n.createdAt ?: now,
                updatedAt = now,
            )
            val existingCards = previous?.let { collection.cards(it.id) }.orEmpty()
            val r = CardGenerator.reconcile(note, existingCards, now, nextNewPosition = { ++position }, newId = newId)
            val fresh = r.created.map { card ->
                val src = n.cards[card.templateKey]
                if (src?.suspended == true) card.copy(suspended = true) else card
            }
            if (progressMode == ProgressMode.KEEP_PROGRESS && scheduler != null) {
                for (card in fresh) {
                    val src = n.cards[card.templateKey] ?: continue
                    val replay = HistoryReplay.replay(card.id, src.reviews, scheduler)
                    history += replay.records
                    if (replay.state != null) {
                        memory[card.id] = replay.state
                        withProgress++
                    } else if (src.scheduledInSource && replay.records.isEmpty()) {
                        withoutHistory++
                    }
                }
            }
            notes += note
            created += fresh
            updated += r.updated
            if (previous == null) added++ else updatedNotes++
            n.fields.values.forEach { mediaNames += MediaRefs.all(it) }
        }

        var copied = 0
        var missing = 0
        if (copyMedia != null) {
            for (name in mediaNames) {
                when (copyMedia(name)) {
                    true -> copied++
                    false -> missing++
                    null -> Unit // already there
                }
            }
        }

        sink.importBatch(ImportBatch(newDecks, notes, created, updated, memory, history))
        return ImportResult(
            notesAdded = added,
            notesUpdated = updatedNotes,
            duplicatesSkipped = dupes,
            incompleteSkipped = incomplete,
            notesSkippedByChoice = skippedByChoice,
            cardsAdded = created.size,
            cardsWithProgress = withProgress,
            reviewsImported = history.size,
            cardsWithoutHistory = withoutHistory,
            mediaCopied = copied,
            mediaMissing = missing,
            decks = (newDecks + decksByName.values).distinctBy { it.id }.filter { d -> notes.any { it.deckId == d.id } },
        )
    }
}

/** Converts source field values into Nihongo Flow fields using a mapping. */
internal object FieldConverter {
    fun convert(values: List<String>, mapping: NoteTypeMapping, html: Boolean = true): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((key, index) in mapping.fields) {
            val raw = values.getOrNull(index) ?: continue
            var text = if (html) AnkiText.toPlain(raw) else raw.trim()
            if (AnkiText.hasFurigana(text)) {
                text = when (key) {
                    Fields.READING, Fields.SENTENCE_READING -> AnkiText.furiganaToKana(text)
                    Fields.WORD, Fields.KANJI, Fields.SENTENCE, Fields.EXAMPLE, Fields.PATTERN -> AnkiText.furiganaToKanji(text)
                    else -> text
                }
            }
            if (text.isNotBlank()) out[key] = text.trim()
        }
        // A reading identical to the word adds nothing (kana-only words).
        if (out[Fields.READING] != null && out[Fields.READING] == out[Fields.WORD]) out.remove(Fields.READING)
        return out
    }
}
