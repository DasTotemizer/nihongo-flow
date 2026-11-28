package app.nihongoflow.core.anki

import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.InMemoryStore
import app.nihongoflow.core.study.NoteKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextAndHtmlTest {

    @Test
    fun `html becomes plain text and media references`() {
        assertEquals("Tokyo", AnkiText.toPlain("<div>Tokyo</div>"))
        assertEquals("book\nvolume", AnkiText.toPlain("book<br>volume"))
        assertEquals("a & b < c", AnkiText.toPlain("a &amp; b&nbsp;&lt; c"))
        assertEquals("ご飯を食べる。", AnkiText.toPlain("ご<b>飯</b>を食べる。"))
        assertEquals("[image:cat.jpg]", AnkiText.toPlain("<img src=\"cat.jpg\">"))
        assertEquals("[image:passwd]", AnkiText.toPlain("<img src=\"../../etc/passwd\">")) // never a path
        assertEquals("x", AnkiText.toPlain("<script>alert(1)</script>x"))
        assertEquals("[sound:a.mp3]", AnkiText.toPlain("[sound:a.mp3]"))
        assertEquals("日本", AnkiText.toPlain("&#26085;&#x672c;"))
    }

    @Test
    fun `anki furigana becomes reading and written form`() {
        assertTrue(AnkiText.hasFurigana("食[た]べる"))
        assertFalse(AnkiText.hasFurigana("[sound:a.mp3]"))
        assertFalse(AnkiText.hasFurigana("たべる"))
        assertEquals("たべる", AnkiText.furiganaToKana("食[た]べる"))
        assertEquals("にほんごをはなす", AnkiText.furiganaToKana("日本語[にほんご]を 話[はな]す"))
        assertEquals("ごはんをたべる", AnkiText.furiganaToKana("ご 飯[はん]を 食[た]べる"))
        assertEquals("日本語を話す", AnkiText.furiganaToKanji("日本語[にほんご]を 話[はな]す"))
    }

    @Test
    fun `Anki text export with headers`() {
        val t = DelimitedText.parse("#separator:tab\n#html:true\n#tags column:3\n食べる\tto <b>eat</b>\tverb n5\n飲む\tto drink\tverb\n")
        assertEquals('\t', t.separator)
        assertTrue(t.html)
        assertEquals(2, t.tagsColumn)
        assertEquals(listOf(listOf("食べる", "to <b>eat</b>", "verb n5"), listOf("飲む", "to drink", "verb")), t.rows)
    }

    @Test
    fun `csv with quotes, commas and line breaks`() {
        val t = DelimitedText.parse("﻿Word,Reading,Meaning\r\n食べる,たべる,\"to eat, to consume\"\r\n本,ほん,\"book\nvolume\"\r\n")
        assertEquals(',', t.separator)
        assertEquals(listOf("Word", "Reading", "Meaning"), t.rows.first())
        assertEquals(listOf("食べる", "たべる", "to eat, to consume"), t.rows[1])
        assertEquals("book\nvolume", t.rows[2][2])
    }

    @Test
    fun `text import makes vocabulary notes in one deck`() = runTest {
        val store = InMemoryStore()
        val importer = TextImporter(store, store)
        val text = DelimitedText.parse("#separator:semicolon\n#columns:Word;Reading;Meaning\n食べる;たべる;to eat\n飲む;のむ;to drink\n;;\n本;ほん;\n")
        val preview = importer.preview(text)
        assertEquals(NoteKind.VOCABULARY, preview.guess.kind)
        val r = importer.import(text, preview.guess, "My words", DuplicatePolicy.SKIP, "words.txt")
        assertEquals(2, r.notesAdded)
        assertEquals(1, r.incompleteSkipped) // 本 has no meaning
        assertEquals(listOf("My words"), store.decks().map { it.name })
        assertEquals(setOf("たべる", "のむ"), store.allNotes().map { it.field(Fields.READING) }.toSet())
        // Both directions are made for text imports, as for cards you add yourself.
        assertEquals(4, store.allNotes().sumOf { store.cards(it.id).size })

        val again = importer.import(text, preview.guess, "My words", DuplicatePolicy.SKIP, "words.txt")
        assertEquals(0, again.notesAdded)
        assertEquals(2, again.duplicatesSkipped)
    }
}
