package app.nihongoflow.core.study

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClozeAndTextTest {

    @Test
    fun `cloze numbers follow Anki numbering`() {
        assertEquals(listOf(1, 2, 3), Cloze.numbers("{{c3::c}} {{c1::a}} {{c2::b}} {{c1::again}}"))
        assertTrue(Cloze.numbers("plain text").isEmpty())
    }

    @Test
    fun `question hides only the active deletion`() {
        val text = "私は{{c1::寿司}}を{{c2::食べる::verb}}。"
        assertEquals("私は[…]を食べる。", Cloze.renderToString(text, 1, revealed = false))
        assertEquals("私は寿司を[verb]。", Cloze.renderToString(text, 2, revealed = false))
        assertEquals("私は寿司を食べる。", Cloze.renderToString(text, 2, revealed = true))
        val answer = Cloze.render(text, 2, revealed = true)
        assertTrue(answer.any { it is Cloze.Segment.Answer && it.text == "食べる" })
    }

    @Test
    fun `normalisation for duplicate checks`() {
        assertEquals("たべる", JapaneseText.normalizeReading("タベル"))
        assertEquals("ｶﾀｶﾅ".let { JapaneseText.normalizeReading(it) }, "かたかな")
        assertEquals(JapaneseText.vocabularyKey("食べる", "たべる"), JapaneseText.vocabularyKey(" 食べる", "タベル"))
        assertTrue(JapaneseText.vocabularyKey("食べる", "たべる") != JapaneseText.vocabularyKey("食事", "しょくじ"))
        assertTrue(JapaneseText.containsKanji("食べる"))
        assertTrue(!JapaneseText.containsKanji("たべる"))
    }

    @Test
    fun `card faces never include raw markup from cloze syntax`() {
        val note = Note("n", "d", NoteKind.CLOZE, mapOf(Fields.TEXT to "駅{{c1::まで}}歩きます。"), createdAt = kotlinx.datetime.Instant.DISTANT_PAST, updatedAt = kotlinx.datetime.Instant.DISTANT_PAST)
        val card = Card("c", "n", "d", CardTemplate.CLOZE, 1, kotlinx.datetime.Instant.DISTANT_PAST, 1)
        val face = CardRenderer.face(note, card)
        assertEquals("駅[…]歩きます。", face.front.single().text)
        assertTrue(face.front.single().japanese)
        assertTrue(face.back.none { "{{" in it.text })
    }
}
