package app.nihongoflow.core.anki

import app.nihongoflow.core.scheduler.FsrsScheduler
import app.nihongoflow.core.study.CardTemplate
import app.nihongoflow.core.study.CollectionRepository
import app.nihongoflow.core.study.ContentSource
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.ImportRepository
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.NoteRules
import app.nihongoflow.core.study.ProgressRepository
import app.nihongoflow.core.study.SourceKind
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.util.UUID

/** What a package contains, shown before anything is imported. */
data class AnkiPreview(
    val format: AnkiFormat,
    val noteTypes: List<NoteTypePreview>,
    val deckNames: List<String>,
    val noteCount: Int,
    val cardCount: Int,
    val suspendedCount: Int,
    /** Answers in the review log (manual changes not counted). */
    val reviewCount: Int,
    val cardsWithHistory: Int,
    val mediaCount: Int,
) {
    val hasProgress: Boolean get() = reviewCount > 0
}

data class NoteTypePreview(
    val noteType: AnkiNoteType,
    val noteCount: Int,
    val cardCount: Int,
    val guess: NoteTypeMapping,
    /** A few of its notes, raw, for showing what a mapping produces. */
    val samples: List<AnkiNote>,
)

/** Counts that depend on the chosen mappings, shown live in the preview. */
data class ImportCheck(
    val alreadyImported: Int,
    val duplicates: Int,
    val incomplete: Int,
    val willImport: Int,
)

data class AnkiImportOptions(
    val mappings: Map<Long, NoteTypeMapping>,
    val progress: ProgressMode = ProgressMode.FRESH_START,
    val duplicates: DuplicatePolicy = DuplicatePolicy.SKIP,
)

/**
 * Imports Anki packages. Preview first ([preview], [check]), then [import] with the
 * learner's choices. Third-party decks are only ever read from the learner's own file.
 */
class AnkiImporter(
    private val collection: CollectionRepository,
    private val progress: ProgressRepository,
    private val sink: ImportRepository,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    fun preview(c: AnkiCollection): AnkiPreview {
        val notesByType = c.notes.groupBy { it.noteTypeId }
        val types = c.noteTypes.filter { it.id in notesByType }.map { nt ->
            val notes = notesByType[nt.id].orEmpty()
            NoteTypePreview(
                noteType = nt,
                noteCount = notes.size,
                cardCount = notes.sumOf { c.cardsByNote[it.id].orEmpty().size },
                guess = MappingGuesser.guess(nt),
                samples = notes.filter { n -> n.fields.any { it.isNotBlank() } }.take(3),
            )
        }.sortedByDescending { it.noteCount }
        val usedDecks = c.cards.map { it.deckId }.toSet()
        val answers = c.reviews.filter { it.ease in 1..4 }
        return AnkiPreview(
            format = c.format,
            noteTypes = types,
            deckNames = c.decks.filter { it.id in usedDecks && !it.filtered }.map { it.name }.sorted(),
            noteCount = c.notes.size,
            cardCount = c.cards.size,
            suspendedCount = c.cards.count { it.suspended },
            reviewCount = answers.size,
            cardsWithHistory = answers.map { it.cardId }.toSet().size,
            mediaCount = c.media.size,
        )
    }

    /** Fields for one note under a mapping, as they'd be saved. For the preview. */
    fun sample(note: AnkiNote, mapping: NoteTypeMapping): Map<String, String> = FieldConverter.convert(note.fields, mapping)

    suspend fun check(c: AnkiCollection, mappings: Map<Long, NoteTypeMapping>): ImportCheck {
        val existing = collection.allNotes()
        val guids = existing.mapNotNull { it.fields[Fields.ANKI_GUID] }.toSet()
        val keys = existing.mapNotNull { n -> NoteRules.duplicateKey(n.kind, n.fields)?.let { "${n.kind}|$it" } }.toMutableSet()
        var already = 0
        var dupes = 0
        var incomplete = 0
        var ok = 0
        for (note in c.notes) {
            val mapping = mappings[note.noteTypeId] ?: continue
            if (mapping.skip) continue
            val fields = FieldConverter.convert(note.fields, mapping)
            if (NoteRules.problem(mapping.kind, fields) != null) { incomplete++; continue }
            if (note.guid in guids) { already++; continue }
            val key = NoteRules.duplicateKey(mapping.kind, fields)?.let { "${mapping.kind}|$it" }
            if (key != null && !keys.add(key)) { dupes++; continue }
            ok++
        }
        return ImportCheck(already, dupes, incomplete, ok)
    }

    /**
     * Imports [pkg]. Media referenced by imported notes is copied into [mediaDir] (files
     * already there are kept). [sourceName] labels the decks and notes, usually the file name.
     */
    suspend fun import(pkg: AnkiPackage, options: AnkiImportOptions, mediaDir: File?, sourceName: String): ImportResult {
        val c = pkg.collection
        val now = clock.now()
        val source = ContentSource(SourceKind.ANKI_IMPORT, sourceName.ifBlank { "Anki" })
        val scheduler = FsrsScheduler(progress.settings().scheduler().copy(enableFuzzing = false))

        var skipped = 0
        var incomplete = 0
        val incoming = mutableListOf<IncomingNote>()
        // Keep Anki's order for new cards: by the position of the note's first new card.
        val order = c.notes.sortedWith(compareBy({ n -> c.cardsByNote[n.id].orEmpty().filter { it.type == 0 }.minOfOrNull { it.due } ?: Long.MAX_VALUE }, { it.id }))
        for (note in order) {
            val nt = c.noteTypesById[note.noteTypeId]
            val mapping = options.mappings[note.noteTypeId] ?: nt?.let { MappingGuesser.guess(it) }
            if (nt == null || mapping == null || mapping.skip) { skipped++; continue }
            val fields = FieldConverter.convert(note.fields, mapping)
            if (NoteRules.problem(mapping.kind, fields) != null) { incomplete++; continue }
            val cards = c.cardsByNote[note.id].orEmpty()
            val deck = cards.minByOrNull { it.ord }?.let { c.decksById[it.deckId] }
            val deckName = deck?.name?.takeUnless { it == "Default" } ?: sourceName.ifBlank { "Imported from Anki" }
            val incomingCards = cards.mapNotNull { card ->
                val key = if (mapping.kind == NoteKind.CLOZE) "cloze:${card.ord + 1}" else mapping.templates[card.ord]?.let { templateKey(it) }
                key?.let { it to IncomingCard(card.suspended, c.reviewsByCard[card.id].orEmpty(), scheduledInSource = card.type != 0) }
            }.toMap()
            incoming += IncomingNote(
                sourceId = note.guid.ifBlank { null },
                kind = mapping.kind,
                fields = fields,
                tags = note.tags.toSet(),
                deckName = deckName,
                createdAt = Instant.fromEpochMilliseconds(note.id).takeIf { it > EARLIEST && it <= now },
                cards = incomingCards,
            )
        }

        val writer = ImportWriter(collection, sink, now, newId, source)
        val copy: ((String) -> Boolean?)? = mediaDir?.let { dir ->
            { name: String ->
                val target = File(dir, name)
                if (target.exists()) null else pkg.copyMedia(name, target)
            }
        }
        return writer.write(incoming, options.duplicates, options.progress, scheduler, skipped, incomplete, copy)
    }

    private fun templateKey(t: CardTemplate) = t.name

    private companion object {
        val EARLIEST = Instant.parse("2005-01-01T00:00:00Z")
    }
}
