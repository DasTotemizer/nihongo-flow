package app.nihongoflow.core.study

/** One line of a card face. The UI decides fonts; this only says what each line is. */
data class FaceLine(
    val text: String,
    val role: Role,
    /** True for Japanese text, so the UI can apply a Japanese locale and font. */
    val japanese: Boolean,
    /** For cloze lines: the segments, so the blank or answer can be styled. */
    val cloze: List<Cloze.Segment>? = null,
) {
    enum class Role { PROMPT, ANSWER, READING, DETAIL, LABEL }
}

/**
 * The two sides of a card. Media are file names in the app's media folder: [frontSounds]
 * play when the question shows, [backSounds] when the answer shows, and images are shown
 * on their side.
 */
data class CardFace(
    val front: List<FaceLine>,
    val back: List<FaceLine>,
    val frontSounds: List<String> = emptyList(),
    val backSounds: List<String> = emptyList(),
    val frontImages: List<String> = emptyList(),
    val backImages: List<String> = emptyList(),
)

/**
 * Builds the question and answer sides of a card from its note. Imported HTML is rendered
 * later by a sanitising renderer; this produces plain text only, so no imported content is
 * ever executed.
 */
object CardRenderer {

    fun face(note: Note, card: Card): CardFace = withMedia(note, card, text(note, card))

    /**
     * Adds the sounds and pictures. Audio that would give the answer away (the word's own
     * audio on a meaning-to-word card) only plays once the answer shows.
     */
    private fun withMedia(note: Note, card: Card, face: CardFace): CardFace {
        val (front, back) = mediaFields(note, card)
        val frontRaw = front.map { note.field(it) }
        val backRaw = back.map { note.field(it) }
        val frontSounds = frontRaw.flatMap { MediaRefs.sounds(it) }.distinct()
        val frontImages = frontRaw.flatMap { MediaRefs.images(it) }.distinct()
        return face.copy(
            frontSounds = frontSounds,
            backSounds = backRaw.flatMap { MediaRefs.sounds(it) }.distinct() - frontSounds.toSet(),
            frontImages = frontImages,
            backImages = (frontImages + backRaw.flatMap { MediaRefs.images(it) }).distinct(),
        )
    }

    /** Which fields' media belong to the question side and which to the answer side. */
    private fun mediaFields(note: Note, card: Card): Pair<List<String>, List<String>> {
        val vocabBack = listOf(Fields.AUDIO, Fields.READING, Fields.MEANING, Fields.SENTENCE, Fields.SENTENCE_AUDIO, Fields.SENTENCE_MEANING, Fields.NOTES, Fields.IMAGE)
        return when (card.template) {
            CardTemplate.RECOGNITION -> listOf(Fields.WORD) to vocabBack
            CardTemplate.PRODUCTION -> listOf(Fields.MEANING) to (listOf(Fields.WORD) + vocabBack)
            CardTemplate.KANJI_MEANING, CardTemplate.KANJI_READING -> listOf(Fields.KANJI) to listOf(Fields.MEANING, Fields.NOTES, Fields.AUDIO, Fields.IMAGE)
            CardTemplate.GRAMMAR -> listOf(Fields.PATTERN) to listOf(Fields.MEANING, Fields.EXPLANATION, Fields.EXAMPLE, Fields.EXAMPLE_MEANING, Fields.NOTES, Fields.AUDIO, Fields.IMAGE)
            CardTemplate.FRONT_TO_BACK -> listOf(Fields.FRONT) to listOf(Fields.BACK, Fields.NOTES, Fields.AUDIO, Fields.IMAGE)
            CardTemplate.BACK_TO_FRONT -> listOf(Fields.BACK) to listOf(Fields.FRONT, Fields.NOTES, Fields.AUDIO, Fields.IMAGE)
            CardTemplate.CLOZE -> listOf(Fields.TEXT) to listOf(Fields.EXTRA, Fields.NOTES, Fields.AUDIO, Fields.IMAGE)
        }
    }

    private fun text(note: Note, card: Card): CardFace = when (card.template) {
        CardTemplate.RECOGNITION -> CardFace(
            front = listOfNotNull(jp(note.field(Fields.WORD), FaceLine.Role.PROMPT)),
            back = vocabularyBack(note, includeWord = true),
        )
        CardTemplate.PRODUCTION -> CardFace(
            front = listOfNotNull(en(note.field(Fields.MEANING), FaceLine.Role.PROMPT)),
            back = vocabularyBack(note, includeWord = true),
        )
        CardTemplate.KANJI_MEANING -> CardFace(
            front = listOfNotNull(jp(note.field(Fields.KANJI), FaceLine.Role.PROMPT)),
            back = kanjiBack(note),
        )
        CardTemplate.KANJI_READING -> CardFace(
            front = listOfNotNull(
                jp(note.field(Fields.KANJI), FaceLine.Role.PROMPT),
                FaceLine("Readings?", FaceLine.Role.LABEL, japanese = false),
            ),
            back = kanjiBack(note),
        )
        CardTemplate.GRAMMAR -> CardFace(
            front = listOfNotNull(jp(note.field(Fields.PATTERN), FaceLine.Role.PROMPT)),
            back = listOfNotNull(
                jp(note.field(Fields.PATTERN), FaceLine.Role.PROMPT),
                en(note.field(Fields.MEANING), FaceLine.Role.ANSWER),
                en(note.field(Fields.EXPLANATION), FaceLine.Role.DETAIL),
                jp(note.field(Fields.EXAMPLE), FaceLine.Role.DETAIL),
                en(note.field(Fields.EXAMPLE_MEANING), FaceLine.Role.DETAIL),
                en(note.field(Fields.NOTES), FaceLine.Role.DETAIL),
            ),
        )
        CardTemplate.FRONT_TO_BACK -> basic(note.field(Fields.FRONT), note.field(Fields.BACK), note)
        CardTemplate.BACK_TO_FRONT -> basic(note.field(Fields.BACK), note.field(Fields.FRONT), note)
        CardTemplate.CLOZE -> {
            val text = note.field(Fields.TEXT)
            val question = Cloze.render(text, card.clozeNumber, revealed = false)
            val answer = Cloze.render(text, card.clozeNumber, revealed = true)
            CardFace(
                front = listOf(FaceLine(question.joinToString("") { it.text }, FaceLine.Role.PROMPT, isJapanese(text), question)),
                back = listOfNotNull(
                    FaceLine(answer.joinToString("") { it.text }, FaceLine.Role.PROMPT, isJapanese(text), answer),
                    en(note.field(Fields.EXTRA), FaceLine.Role.DETAIL),
                    en(note.field(Fields.NOTES), FaceLine.Role.DETAIL),
                ),
            )
        }
    }

    private fun vocabularyBack(note: Note, includeWord: Boolean) = listOfNotNull(
        if (includeWord) jp(note.field(Fields.WORD), FaceLine.Role.PROMPT) else null,
        jp(note.field(Fields.READING), FaceLine.Role.READING),
        en(note.field(Fields.MEANING), FaceLine.Role.ANSWER),
        jp(note.field(Fields.PITCH), FaceLine.Role.DETAIL),
        jp(note.field(Fields.SENTENCE), FaceLine.Role.DETAIL),
        jp(note.field(Fields.SENTENCE_READING), FaceLine.Role.READING),
        en(note.field(Fields.SENTENCE_MEANING), FaceLine.Role.DETAIL),
        en(note.field(Fields.NOTES), FaceLine.Role.DETAIL),
    )

    private fun kanjiBack(note: Note) = listOfNotNull(
        jp(note.field(Fields.KANJI), FaceLine.Role.PROMPT),
        en(note.field(Fields.MEANING), FaceLine.Role.ANSWER),
        note.field(Fields.ONYOMI).takeIf { it.isNotBlank() }?.let { FaceLine("On: $it", FaceLine.Role.READING, true) },
        note.field(Fields.KUNYOMI).takeIf { it.isNotBlank() }?.let { FaceLine("Kun: $it", FaceLine.Role.READING, true) },
        en(note.field(Fields.NOTES), FaceLine.Role.DETAIL),
    )

    private fun basic(front: String, back: String, note: Note) = CardFace(
        front = listOfNotNull(line(front, FaceLine.Role.PROMPT)),
        back = listOfNotNull(
            line(front, FaceLine.Role.PROMPT),
            line(back, FaceLine.Role.ANSWER),
            line(note.field(Fields.NOTES), FaceLine.Role.DETAIL),
        ),
    )

    private fun line(text: String, role: FaceLine.Role) =
        MediaRefs.withoutRefs(text).takeIf { it.isNotBlank() }?.let { FaceLine(it, role, isJapanese(it)) }

    private fun jp(text: String, role: FaceLine.Role) =
        MediaRefs.withoutRefs(text).takeIf { it.isNotBlank() }?.let { FaceLine(it, role, japanese = true) }

    private fun en(text: String, role: FaceLine.Role) =
        MediaRefs.withoutRefs(text).takeIf { it.isNotBlank() }?.let { FaceLine(it, role, isJapanese(it)) }

    /** True when the text contains kana or kanji. */
    fun isJapanese(text: String): Boolean = text.any { c ->
        c in '぀'..'ヿ' || JapaneseText.isKanji(c)
    }
}
