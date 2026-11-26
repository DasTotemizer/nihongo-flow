package app.nihongoflow.core.anki

import app.nihongoflow.core.model.MemoryPhase
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.model.ReviewSource
import app.nihongoflow.core.study.CardTemplate
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.InMemoryStore
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.SaveNoteResult
import app.nihongoflow.core.study.StudyService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnkiImporterTest {

    @TempDir
    lateinit var tmp: File

    private val store = InMemoryStore()
    private val importer = AnkiImporter(store, store, store)
    private val study = StudyService(store, store)

    private suspend fun import(
        name: String,
        mode: ProgressMode = ProgressMode.KEEP_PROGRESS,
        duplicates: DuplicatePolicy = DuplicatePolicy.SKIP,
    ): ImportResult = AnkiPackageReader(JdbcSql.opener, File(tmp, "work")).read(Fixtures.file(name)).use { pkg ->
        val mappings = importer.preview(pkg.collection).noteTypes.associate { it.noteType.id to it.guess }
        importer.import(pkg, AnkiImportOptions(mappings, mode, duplicates), File(tmp, "media"), name)
    }

    private suspend fun noteWith(word: String) = store.allNotes().single { n -> n.fields.values.any { it.startsWith(word) } }

    @Test
    fun `preview counts what is inside`() = runTest {
        AnkiPackageReader(JdbcSql.opener, tmp).read(Fixtures.file("modern.apkg")).use { pkg ->
            val p = importer.preview(pkg.collection)
            assertEquals(6, p.noteCount)
            assertEquals(8, p.cardCount)
            assertEquals(1, p.suspendedCount)
            assertEquals(6, p.reviewCount) // 7 log lines, one of them Anki's manual "Forget"
            assertEquals(2, p.cardsWithHistory)
            assertEquals(listOf("Japanese::Vocab", "Misc"), p.deckNames)
            val check = importer.check(pkg.collection, p.noteTypes.associate { it.noteType.id to it.guess })
            assertEquals(ImportCheck(alreadyImported = 0, duplicates = 0, incomplete = 0, willImport = 6), check)
        }
    }

    @Test
    fun `keeping progress replays the real review history through FSRS`() = runTest {
        val result = import("modern.apkg")
        assertEquals(6, result.notesAdded)
        assertEquals(6, result.reviewsImported)
        assertEquals(1, result.cardsWithProgress)
        assertEquals(1, result.cardsWithoutHistory)
        assertEquals(1, result.mediaCopied)
        assertEquals("ID3fake-audio", File(tmp, "media/taberu.mp3").readText())
        assertEquals(setOf("Japanese::Vocab", "Misc"), store.decks().map { it.name }.toSet())

        val taberu = noteWith("食べる")
        assertEquals(NoteKind.VOCABULARY, taberu.kind)
        assertEquals("たべる", taberu.field(Fields.READING))
        assertEquals("to eat", taberu.field(Fields.MEANING))
        assertEquals("ご飯を食べる。", taberu.field(Fields.SENTENCE))
        assertEquals("Ichidan verb & common", taberu.field(Fields.NOTES))
        assertEquals("[sound:taberu.mp3]", taberu.field(Fields.AUDIO))
        // Anki had only a recognition card, so there's no new production card.
        val card = store.cards(taberu.id).single()
        assertEquals(CardTemplate.RECOGNITION, card.template)

        val history = store.reviewsFor(card.id)
        assertEquals(listOf(Rating.GOOD, Rating.GOOD, Rating.AGAIN, Rating.GOOD, Rating.EASY), history.map { it.rating })
        assertTrue(history.all { it.source == ReviewSource.ANKI_IMPORT })
        history.zipWithNext().forEach { (a, b) -> assertEquals(a.stateAfter, b.stateBefore) }
        val memory = store.memory(card.id)!!
        assertEquals(history.last().stateAfter, memory)
        assertEquals(MemoryPhase.REVIEW, memory.phase)
        assertEquals(history.last().reviewedAt, memory.lastReview)

        // 飲む was reset in Anki: its answer is kept as history, the card starts new.
        val nomu = store.cards(noteWith("飲む").id).single()
        assertEquals(1, store.reviewsFor(nomu.id).size)
        assertNull(store.memory(nomu.id))

        // 本 had a schedule in Anki but no history: it starts new rather than with made-up memory.
        assertNull(store.memory(store.cards(noteWith("本").id).single().id))

        val inu = store.cards(noteWith("犬").id).associateBy { it.template }
        assertTrue(inu.getValue(CardTemplate.BACK_TO_FRONT).suspended)
        assertTrue(!inu.getValue(CardTemplate.FRONT_TO_BACK).suspended)

        val cloze = noteWith("私は")
        assertEquals(NoteKind.CLOZE, cloze.kind)
        assertEquals(setOf(1, 2), store.cards(cloze.id).map { it.clozeNumber }.toSet())
        assertEquals("Tokyo", noteWith("Capital").field(Fields.BACK))
    }

    @Test
    fun `old and new formats import identically`() = runTest {
        val a = import("legacy.apkg")
        val legacyNotes = store.allNotes().map { it.kind to it.fields - Fields.ANKI_GUID }.toSet()
        val legacyHistory = store.reviewsSince(kotlinx.datetime.Instant.DISTANT_PAST).map { it.rating to it.stateAfter }
        val other = InMemoryStore()
        val b = AnkiPackageReader(JdbcSql.opener, File(tmp, "w2")).read(Fixtures.file("modern.apkg")).use { pkg ->
            val imp = AnkiImporter(other, other, other)
            imp.import(pkg, AnkiImportOptions(imp.preview(pkg.collection).noteTypes.associate { it.noteType.id to it.guess }, ProgressMode.KEEP_PROGRESS), null, "x")
        }
        assertEquals(a.copy(decks = emptyList(), mediaCopied = 0), b.copy(decks = emptyList(), mediaCopied = 0))
        assertEquals(legacyNotes, other.allNotes().map { it.kind to it.fields - Fields.ANKI_GUID }.toSet())
        assertEquals(legacyHistory, other.reviewsSince(kotlinx.datetime.Instant.DISTANT_PAST).map { it.rating to it.stateAfter })
    }

    @Test
    fun `fresh start imports notes without any history`() = runTest {
        val result = import("modern.apkg", ProgressMode.FRESH_START)
        assertEquals(6, result.notesAdded)
        assertEquals(0, result.reviewsImported)
        assertTrue(store.allMemory().isEmpty())
        assertEquals(0, store.reviewCount())
        assertEquals(7, study.home().cardCount) // 8 cards, one suspended
    }

    @Test
    fun `importing the same deck again updates instead of duplicating`() = runTest {
        import("modern.apkg")
        val cards = store.allNotes().sumOf { store.cards(it.id).size }
        val reviews = store.reviewCount()
        val again = import("modern.apkg")
        assertEquals(0, again.notesAdded)
        assertEquals(6, again.notesUpdated)
        assertEquals(0, again.cardsAdded)
        assertEquals(0, again.reviewsImported)
        assertEquals(6, store.allNotes().size)
        assertEquals(cards, store.allNotes().sumOf { store.cards(it.id).size })
        assertEquals(reviews, store.reviewCount())
        assertEquals(2, store.decks().size)
    }

    @Test
    fun `notes you already have are skipped unless you choose otherwise`() = runTest {
        val deck = study.createDeck("Mine")
        assertIs<SaveNoteResult.Saved>(
            study.saveNote(deck.id, NoteKind.VOCABULARY, mapOf(Fields.WORD to "食べる", Fields.READING to "タベル", Fields.MEANING to "eat")),
        )
        val skipped = import("shared.apkg")
        assertEquals(1, skipped.duplicatesSkipped)
        assertEquals(5, skipped.notesAdded)

        val other = InMemoryStore()
        val s2 = StudyService(other, other)
        val d2 = s2.createDeck("Mine")
        s2.saveNote(d2.id, NoteKind.VOCABULARY, mapOf(Fields.WORD to "食べる", Fields.READING to "たべる", Fields.MEANING to "eat"))
        val imp = AnkiImporter(other, other, other)
        val anyway = AnkiPackageReader(JdbcSql.opener, File(tmp, "w3")).read(Fixtures.file("shared.apkg")).use { pkg ->
            imp.import(pkg, AnkiImportOptions(imp.preview(pkg.collection).noteTypes.associate { it.noteType.id to it.guess }, duplicates = DuplicatePolicy.IMPORT_ANYWAY), null, "x")
        }
        assertEquals(0, anyway.duplicatesSkipped)
        assertEquals(6, anyway.notesAdded)
    }

    @Test
    fun `editing an imported note keeps its Anki identity and card limit`() = runTest {
        import("modern.apkg")
        val taberu = noteWith("食べる")
        study.saveNote(taberu.deckId, taberu.kind, taberu.fields + (Fields.MEANING to "to eat; to live on"), existingId = taberu.id)
        val edited = store.note(taberu.id)!!
        assertEquals(taberu.field(Fields.ANKI_GUID), edited.field(Fields.ANKI_GUID))
        assertEquals(1, store.cards(taberu.id).size)
    }

    @Test
    fun `a note type can be skipped or mapped differently`() = runTest {
        AnkiPackageReader(JdbcSql.opener, tmp).read(Fixtures.file("modern.apkg")).use { pkg ->
            val p = importer.preview(pkg.collection)
            val mappings = p.noteTypes.associate { t ->
                t.noteType.id to when (t.noteType.name) {
                    "Cloze" -> t.guess.copy(skip = true)
                    "Basic" -> t.guess.withKind(NoteKind.BASIC_AND_REVERSED, t.noteType.fields, t.noteType.templates)
                    else -> t.guess
                }
            }
            val r = importer.import(pkg, AnkiImportOptions(mappings), null, "x")
            assertEquals(5, r.notesAdded)
            assertEquals(1, r.notesSkippedByChoice)
            // Anki had one card for Basic, so only that one is made even as "both directions".
            assertEquals(1, store.cards(noteWith("Capital").id).size)
        }
    }
}

