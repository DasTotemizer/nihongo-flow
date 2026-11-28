package app.nihongoflow.core.anki

import app.nihongoflow.core.study.CardTemplate
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.NoteKind
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fixtures were written by the real Anki library (tools/anki-fixtures/make_fixtures.py). */
class AnkiPackageReaderTest {

    @TempDir
    lateinit var tmp: File

    private fun read(name: String) = AnkiPackageReader(JdbcSql.opener, tmp).read(Fixtures.file(name))

    @Test
    fun `old and new formats read the same collection`() {
        val packages = listOf("legacy.apkg" to AnkiFormat.ANKI21, "modern.apkg" to AnkiFormat.ANKI21B, "backup.colpkg" to AnkiFormat.ANKI21B)
        val contents = packages.map { (name, format) ->
            read(name).use { pkg ->
                val c = pkg.collection
                assertEquals(format, c.format, name)
                val used = c.notes.map { it.noteTypeId }.toSet()
                listOf(
                    c.noteTypes.filter { it.id in used }.map { Triple(it.name, it.isCloze, it.fields) }.sortedBy { it.first },
                    c.noteTypes.filter { it.id in used }.associate { it.name to it.templates.map { t -> t.ord to t.front } },
                    c.decks.filter { !it.filtered }.map { it.name }.sorted(),
                    c.notes.map { it.guid to it.fields },
                    c.cards.map { listOf(it.ord, it.type, it.queue) },
                    c.reviews.map { listOf(it.ease, it.type) },
                    c.media.map { it.name },
                )
            }
        }
        assertEquals(contents[0], contents[1])
        assertEquals(contents[1], contents[2])
    }

    @Test
    fun `note types, fields, templates and decks come through`() = read("modern.apkg").use { pkg ->
        val c = pkg.collection
        val vocab = c.noteTypes.single { it.name == "Japanese Vocab" }
        assertEquals(listOf("Word", "Word Reading", "Word Meaning", "Word Audio", "Sentence", "Sentence Meaning", "Notes"), vocab.fields)
        assertEquals("<div class=jp>{{Word}}</div>", vocab.templates.single().front)
        assertTrue(c.noteTypes.single { it.name == "Cloze" }.isCloze)
        assertFalse(c.noteTypes.single { it.name == "Basic" }.isCloze)
        assertTrue("Japanese::Vocab" in c.decks.map { it.name })
        val taberu = c.notes.single { it.fields[0] == "食べる" }
        assertEquals(listOf("jlpt::n5", "verb"), taberu.tags.sorted())
        assertEquals(5, c.reviewsByCard[c.cardsByNote.getValue(taberu.id).single().id]!!.size)
        assertEquals(1, c.cards.count { it.suspended })
    }

    @Test
    fun `shared decks carry no scheduling`() = read("shared.apkg").use { pkg ->
        assertTrue(pkg.collection.reviews.isEmpty())
        assertTrue(pkg.collection.cards.all { it.type == 0 })
    }

    @Test
    fun `media is listed and copied in both formats`() {
        for (name in listOf("legacy.apkg", "modern.apkg")) read(name).use { pkg ->
            assertEquals(listOf("taberu.mp3"), pkg.collection.media.map { it.name })
            val target = File(tmp, "$name-media/taberu.mp3")
            assertTrue(pkg.copyMedia("taberu.mp3", target))
            assertEquals("ID3fake-audio", target.readText())
            assertFalse(pkg.copyMedia("missing.mp3", File(tmp, "x")))
        }
    }

    @Test
    fun `files that are not Anki packages get a clear message`() {
        val notZip = File(tmp, "notes.apkg").apply { writeText("hello") }
        assertFailsWith<AnkiImportException> { AnkiPackageReader(JdbcSql.opener, tmp).read(notZip) }
        val emptyZip = File(tmp, "empty.apkg")
        java.util.zip.ZipOutputStream(emptyZip.outputStream()).use { it.putNextEntry(java.util.zip.ZipEntry("x")); it.write(1) }
        assertFailsWith<AnkiImportException> { AnkiPackageReader(JdbcSql.opener, tmp).read(emptyZip) }
    }

    @Test
    fun `collation patch replaces the declaration across buffer boundaries`() {
        val input = "abc COLLATE unicase, x collate unicase COLLATE uni COLLATEunicase COLLATE unicase".toByteArray()
        for (chunk in listOf(1, 3, 7, 16, input.size)) {
            val out = ByteArrayOutputStream()
            CollationPatch(out).use { p -> input.toList().chunked(chunk).forEach { p.write(it.toByteArray()) } }
            assertEquals("abc COLLATE NOCASE , x collate NOCASE  COLLATE uni COLLATEunicase COLLATE NOCASE ", out.toString(), "chunk $chunk")
            assertEquals(input.size, out.size())
        }
    }

    @Test
    fun `mapping guesses fit common note types`() = read("modern.apkg").use { pkg ->
        val types = pkg.collection.noteTypes.associateBy { it.name }
        val vocab = MappingGuesser.guess(types.getValue("Japanese Vocab"))
        assertEquals(NoteKind.VOCABULARY, vocab.kind)
        assertEquals(mapOf(Fields.WORD to 0, Fields.READING to 1, Fields.MEANING to 2, Fields.SENTENCE to 4, Fields.SENTENCE_MEANING to 5, Fields.NOTES to 6, Fields.AUDIO to 3), vocab.fields)
        assertEquals(mapOf(0 to CardTemplate.RECOGNITION), vocab.templates)
        assertEquals(NoteKind.BASIC, MappingGuesser.guess(types.getValue("Basic")).kind)
        val reversed = MappingGuesser.guess(types.getValue("Basic (and reversed card)"))
        assertEquals(NoteKind.BASIC_AND_REVERSED, reversed.kind)
        assertEquals(mapOf(0 to CardTemplate.FRONT_TO_BACK, 1 to CardTemplate.BACK_TO_FRONT), reversed.templates)
        assertEquals(NoteKind.CLOZE, MappingGuesser.guess(types.getValue("Cloze")).kind)
    }

    @Test
    fun `Kaishi-style field names map to vocabulary fields`() {
        val names = listOf("Word", "Word Reading", "Word Meaning", "Word Furigana", "Word Audio", "Sentence", "Sentence Meaning",
            "Sentence Furigana", "Sentence Audio", "Notes", "Pitch Accent", "Pitch Accent Notes", "Frequency", "Picture")
        assertEquals(NoteKind.VOCABULARY, MappingGuesser.guessKind(false, names))
        val f = MappingGuesser.guessFields(NoteKind.VOCABULARY, names)
        assertEquals(0, f[Fields.WORD]); assertEquals(1, f[Fields.READING]); assertEquals(2, f[Fields.MEANING])
        assertEquals(5, f[Fields.SENTENCE]); assertEquals(6, f[Fields.SENTENCE_MEANING]); assertEquals(7, f[Fields.SENTENCE_READING])
        assertEquals(10, f[Fields.PITCH]); assertEquals(9, f[Fields.NOTES])
        assertEquals(4, f[Fields.AUDIO]); assertEquals(8, f[Fields.SENTENCE_AUDIO]); assertEquals(13, f[Fields.IMAGE])
    }
}
