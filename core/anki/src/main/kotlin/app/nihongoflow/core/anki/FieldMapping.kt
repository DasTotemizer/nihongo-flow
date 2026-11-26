package app.nihongoflow.core.anki

import app.nihongoflow.core.study.CardTemplate
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.NoteKind

/** A Nihongo Flow field a source column or Anki field can fill. */
data class TargetField(val key: String, val label: String, val required: Boolean = false)

object TargetFields {
    fun of(kind: NoteKind): List<TargetField> = when (kind) {
        NoteKind.VOCABULARY -> listOf(
            TargetField(Fields.WORD, "Word", required = true),
            TargetField(Fields.READING, "Reading"),
            TargetField(Fields.MEANING, "Meaning", required = true),
            TargetField(Fields.SENTENCE, "Example sentence"),
            TargetField(Fields.SENTENCE_READING, "Sentence reading"),
            TargetField(Fields.SENTENCE_MEANING, "Sentence translation"),
            TargetField(Fields.PITCH, "Pitch accent"),
            TargetField(Fields.NOTES, "Notes"),
            TargetField(Fields.AUDIO, "Word audio"),
            TargetField(Fields.SENTENCE_AUDIO, "Sentence audio"),
            TargetField(Fields.IMAGE, "Picture"),
        )
        NoteKind.KANJI -> listOf(
            TargetField(Fields.KANJI, "Kanji", required = true),
            TargetField(Fields.MEANING, "Meanings", required = true),
            TargetField(Fields.ONYOMI, "On'yomi"),
            TargetField(Fields.KUNYOMI, "Kun'yomi"),
            TargetField(Fields.NOTES, "Notes"),
        )
        NoteKind.GRAMMAR -> listOf(
            TargetField(Fields.PATTERN, "Pattern", required = true),
            TargetField(Fields.MEANING, "Meaning", required = true),
            TargetField(Fields.EXPLANATION, "Explanation"),
            TargetField(Fields.EXAMPLE, "Example sentence"),
            TargetField(Fields.EXAMPLE_MEANING, "Example translation"),
            TargetField(Fields.NOTES, "Notes"),
        )
        NoteKind.BASIC, NoteKind.BASIC_AND_REVERSED -> listOf(
            TargetField(Fields.FRONT, "Front", required = true),
            TargetField(Fields.BACK, "Back", required = true),
            TargetField(Fields.NOTES, "Notes"),
        )
        NoteKind.CLOZE -> listOf(
            TargetField(Fields.TEXT, "Cloze text", required = true),
            TargetField(Fields.EXTRA, "Extra"),
        )
    }
}

/**
 * How one Anki note type (or one text file) becomes Nihongo Flow notes.
 *
 * @property fields Nihongo Flow field key → index of the source field. Several keys may read
 *   the same source (for example a furigana field gives both word and reading).
 * @property templates Anki card template ord → the Nihongo Flow card it becomes, so review
 *   history follows the right card. Not used for cloze, where ord n is cloze n+1.
 * @property skip leave these notes out of the import.
 */
data class NoteTypeMapping(
    val kind: NoteKind,
    val fields: Map<String, Int>,
    val templates: Map<Int, CardTemplate> = emptyMap(),
    val skip: Boolean = false,
) {
    /** The same mapping for another kind, keeping what still fits and guessing the rest. */
    fun withKind(newKind: NoteKind, sourceFields: List<String>, sourceTemplates: List<AnkiTemplate> = emptyList()): NoteTypeMapping {
        if (newKind == kind) return this
        val guessed = MappingGuesser.guessFields(newKind, sourceFields)
        val keys = TargetFields.of(newKind).map { it.key }.toSet()
        val kept = fields.filterKeys { it in keys }
        val merged = guessed + kept
        return copy(kind = newKind, fields = merged, templates = MappingGuesser.guessTemplates(newKind, merged, sourceFields, sourceTemplates))
    }
}

/** Guesses a sensible mapping from field names. The learner can change it in the preview. */
object MappingGuesser {

    private fun norm(name: String) = name.lowercase().replace(Regex("[^a-z0-9\\p{L}]+"), " ").trim()

    private val word = listOf("word", "expression", "vocab", "vocabulary", "japanese", "term", "単語", "語彙", "front")
    private val reading = listOf("reading", "kana", "hiragana", "furigana", "yomi", "読み", "よみ")
    private val meaning = listOf("meaning", "english", "definition", "glossary", "gloss", "translation", "意味", "back")
    private val sentence = listOf("sentence", "example", "context", "例文")
    private val kanjiNames = listOf("kanji", "漢字", "character")
    private val onNames = listOf("onyomi", "on yomi", "on reading", "音読み")
    private val kunNames = listOf("kunyomi", "kun yomi", "kun reading", "訓読み")
    private val grammarNames = listOf("grammar", "pattern", "grammar point", "文法")

    private fun find(sourceFields: List<String>, used: Set<Int> = emptySet(), test: (String) -> Boolean): Int? =
        sourceFields.indices.firstOrNull { it !in used && test(norm(sourceFields[it])) }

    private fun List<String>.matches(name: String) = any { k -> name == k || name.startsWith("$k ") || name.endsWith(" $k") }

    fun guess(noteType: AnkiNoteType): NoteTypeMapping {
        val kind = guessKind(noteType.isCloze, noteType.fields, noteType.templates.size)
        val fields = guessFields(kind, noteType.fields)
        return NoteTypeMapping(kind, fields, guessTemplates(kind, fields, noteType.fields, noteType.templates))
    }

    fun guessKind(isCloze: Boolean, sourceFields: List<String>, templateCount: Int = 1): NoteKind {
        if (isCloze) return NoteKind.CLOZE
        val names = sourceFields.map { norm(it) }
        val has = { list: List<String> -> names.any { list.matches(it) } }
        return when {
            has(grammarNames) -> NoteKind.GRAMMAR
            names.any { it in kanjiNames } && (has(onNames) || has(kunNames)) -> NoteKind.KANJI
            names.any { n -> word.dropLast(1).matches(n) } && has(meaning) -> NoteKind.VOCABULARY
            templateCount >= 2 -> NoteKind.BASIC_AND_REVERSED
            else -> NoteKind.BASIC
        }
    }

    fun guessFields(kind: NoteKind, src: List<String>): Map<String, Int> {
        val m = mutableMapOf<String, Int>()
        fun put(key: String, index: Int?) { if (index != null) m[key] = index }
        val sentenceIdx = find(src) { n -> sentence.matches(n) && !meaning.matches(n) && !reading.matches(n) && !n.contains("audio") && !n.contains("sound") }
        when (kind) {
            NoteKind.VOCABULARY -> {
                val w = find(src) { n -> word.dropLast(1).any { n == it } } ?: find(src) { n ->
                    word.dropLast(1).matches(n) && !meaning.matches(n) && !reading.matches(n) && !n.contains("audio") && !sentence.matches(n)
                } ?: 0
                put(Fields.WORD, w)
                put(Fields.READING, find(src, setOf(w)) { n -> reading.matches(n) && !sentence.matches(n) })
                put(Fields.MEANING, find(src, setOf(w)) { n -> meaning.matches(n) && !sentence.matches(n) } ?: if (src.size > 1 && w != 1) 1 else null)
                put(Fields.SENTENCE, sentenceIdx)
                put(Fields.SENTENCE_READING, find(src) { n -> sentence.matches(n) && reading.matches(n) })
                put(Fields.SENTENCE_MEANING, find(src) { n -> sentence.matches(n) && meaning.matches(n) })
                put(Fields.PITCH, find(src) { n -> n.contains("pitch") && !n.contains("note") })
                put(Fields.NOTES, find(src) { n -> n == "notes" || n == "note" || n == "comments" })
                put(Fields.AUDIO, find(src) { n -> (n.contains("audio") || n.contains("sound")) && !sentence.matches(n) })
                put(Fields.SENTENCE_AUDIO, find(src) { n -> (n.contains("audio") || n.contains("sound")) && sentence.matches(n) })
                put(Fields.IMAGE, find(src) { n -> n == "picture" || n == "image" || n == "pic" })
            }
            NoteKind.KANJI -> {
                val k = find(src) { it in kanjiNames } ?: 0
                put(Fields.KANJI, k)
                put(Fields.MEANING, find(src, setOf(k)) { meaning.matches(it) } ?: if (src.size > 1 && k != 1) 1 else null)
                put(Fields.ONYOMI, find(src) { onNames.matches(it) })
                put(Fields.KUNYOMI, find(src) { kunNames.matches(it) })
                put(Fields.NOTES, find(src) { it == "notes" || it == "note" || it.contains("mnemonic") })
            }
            NoteKind.GRAMMAR -> {
                val g = find(src) { grammarNames.matches(it) } ?: 0
                put(Fields.PATTERN, g)
                put(Fields.MEANING, find(src, setOf(g)) { meaning.matches(it) && !sentence.matches(it) } ?: if (src.size > 1 && g != 1) 1 else null)
                put(Fields.EXPLANATION, find(src) { it.contains("explanation") || it.contains("usage") })
                put(Fields.EXAMPLE, sentenceIdx)
                put(Fields.EXAMPLE_MEANING, find(src) { sentence.matches(it) && meaning.matches(it) })
                put(Fields.NOTES, find(src) { it == "notes" || it == "note" })
            }
            NoteKind.BASIC, NoteKind.BASIC_AND_REVERSED -> {
                val f = find(src) { it == "front" } ?: 0
                put(Fields.FRONT, f)
                put(Fields.BACK, find(src) { it == "back" } ?: if (src.size > 1) (if (f == 1) 0 else 1) else null)
                put(Fields.NOTES, find(src) { it == "notes" || it == "note" || it == "extra" })
            }
            NoteKind.CLOZE -> {
                put(Fields.TEXT, find(src) { it == "text" } ?: 0)
                put(Fields.EXTRA, find(src) { it == "back extra" || it == "extra" } ?: if (src.size > 1) 1 else null)
            }
        }
        return m.filterValues { it in src.indices }
    }

    private val fieldRef = Regex("""\{\{([^{}]+)}}""")

    /** Source field names a template shows, from `{{Field}}` and `{{filter:Field}}`. */
    fun referencedFields(template: String): Set<String> =
        fieldRef.findAll(template).map { it.groupValues[1].trim() }
            .filter { !it.startsWith("#") && !it.startsWith("/") && !it.startsWith("^") }
            .map { it.substringAfterLast(':').trim() }
            .toSet()

    fun guessTemplates(kind: NoteKind, fields: Map<String, Int>, src: List<String>, templates: List<AnkiTemplate>): Map<Int, CardTemplate> {
        val ords = templates.map { it.ord }.ifEmpty { listOf(0) }
        fun asked(t: AnkiTemplate?, key: String): Boolean {
            val idx = fields[key] ?: return false
            return t != null && src.getOrNull(idx) in referencedFields(t.front)
        }
        return when (kind) {
            NoteKind.CLOZE -> emptyMap()
            NoteKind.VOCABULARY -> {
                val out = mutableMapOf<Int, CardTemplate>()
                for (ord in ords) {
                    val t = templates.firstOrNull { it.ord == ord }
                    val production = asked(t, Fields.MEANING) && !asked(t, Fields.WORD)
                    val choice = if (production) CardTemplate.PRODUCTION else CardTemplate.RECOGNITION
                    if (choice !in out.values) out[ord] = choice
                    else (listOf(CardTemplate.RECOGNITION, CardTemplate.PRODUCTION) - out.values.toSet()).firstOrNull()?.let { out[ord] = it }
                }
                out
            }
            NoteKind.KANJI -> ords.zip(listOf(CardTemplate.KANJI_MEANING, CardTemplate.KANJI_READING)).toMap()
            NoteKind.GRAMMAR -> mapOf(ords.first() to CardTemplate.GRAMMAR)
            NoteKind.BASIC -> mapOf(ords.first() to CardTemplate.FRONT_TO_BACK)
            NoteKind.BASIC_AND_REVERSED -> {
                val out = mutableMapOf<Int, CardTemplate>()
                for (ord in ords) {
                    val t = templates.firstOrNull { it.ord == ord }
                    val reverse = asked(t, Fields.BACK) && !asked(t, Fields.FRONT)
                    val choice = if (reverse) CardTemplate.BACK_TO_FRONT else CardTemplate.FRONT_TO_BACK
                    if (choice !in out.values) out[ord] = choice
                    else (listOf(CardTemplate.FRONT_TO_BACK, CardTemplate.BACK_TO_FRONT) - out.values.toSet()).firstOrNull()?.let { out[ord] = it }
                }
                out
            }
        }
    }
}
