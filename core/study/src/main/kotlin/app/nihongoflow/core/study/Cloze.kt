package app.nihongoflow.core.study

/**
 * Anki-compatible cloze syntax: `{{c1::answer}}` or `{{c1::answer::hint}}`. Numbering is
 * kept exactly, so imported Anki cloze notes map one card per number as in Anki.
 */
object Cloze {

    private val pattern = Regex("""\{\{c(\d+)::(.*?)(?:::(.*?))?\}\}""", RegexOption.DOT_MATCHES_ALL)

    data class Deletion(val number: Int, val answer: String, val hint: String?)

    sealed interface Segment {
        val text: String

        data class Plain(override val text: String) : Segment

        /** Hidden on the question side: shows the hint, or "[…]". */
        data class Blank(val hint: String?) : Segment {
            override val text: String get() = if (hint.isNullOrBlank()) "[…]" else "[$hint]"
        }

        /** The answer, highlighted on the answer side. */
        data class Answer(override val text: String) : Segment
    }

    fun deletions(text: String): List<Deletion> =
        pattern.findAll(text).map { m ->
            Deletion(m.groupValues[1].toInt(), m.groupValues[2], m.groups[3]?.value)
        }.toList()

    /** Cloze numbers present in [text], in ascending order. Number 0 is not valid in Anki. */
    fun numbers(text: String): List<Int> = deletions(text).map { it.number }.filter { it > 0 }.distinct().sorted()

    /**
     * Renders [text] for the card with [activeNumber]. Other deletions show their answers as
     * plain text, as in Anki.
     */
    fun render(text: String, activeNumber: Int, revealed: Boolean): List<Segment> {
        val segments = mutableListOf<Segment>()
        var last = 0
        for (m in pattern.findAll(text)) {
            if (m.range.first > last) segments += Segment.Plain(text.substring(last, m.range.first))
            val number = m.groupValues[1].toInt()
            val answer = m.groupValues[2]
            segments += when {
                number != activeNumber -> Segment.Plain(answer)
                revealed -> Segment.Answer(answer)
                else -> Segment.Blank(m.groups[3]?.value)
            }
            last = m.range.last + 1
        }
        if (last < text.length) segments += Segment.Plain(text.substring(last))
        return mergePlain(segments)
    }

    fun renderToString(text: String, activeNumber: Int, revealed: Boolean): String =
        render(text, activeNumber, revealed).joinToString("") { it.text }

    private fun mergePlain(segments: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in segments) {
            val prev = out.lastOrNull()
            if (s is Segment.Plain && prev is Segment.Plain) out[out.lastIndex] = Segment.Plain(prev.text + s.text)
            else out += s
        }
        return out
    }
}
