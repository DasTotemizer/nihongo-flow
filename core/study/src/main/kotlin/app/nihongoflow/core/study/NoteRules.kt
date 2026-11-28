package app.nihongoflow.core.study

/** Rules shared by manual card creation and imports. */
object NoteRules {
    /** Two notes of the same kind with the same key are the same item. */
    fun duplicateKey(kind: NoteKind, f: Map<String, String>): String? = when (kind) {
        NoteKind.VOCABULARY -> f[Fields.WORD]?.let { JapaneseText.vocabularyKey(it, f[Fields.READING].orEmpty()) }
        NoteKind.KANJI -> f[Fields.KANJI]?.let { JapaneseText.normalize(it) }
        NoteKind.GRAMMAR -> f[Fields.PATTERN]?.let { JapaneseText.normalize(it) }
        NoteKind.BASIC, NoteKind.BASIC_AND_REVERSED -> f[Fields.FRONT]?.let { JapaneseText.normalize(it) }
        NoteKind.CLOZE -> f[Fields.TEXT]?.let { JapaneseText.normalize(it) }
    }?.takeIf { it.isNotBlank() }

    /** Why a note can't be saved, or null if it can. [f] holds trimmed, non-empty values only. */
    fun problem(kind: NoteKind, f: Map<String, String>): String? = when (kind) {
        NoteKind.VOCABULARY -> if (f[Fields.WORD] == null || f[Fields.MEANING] == null) "Add the word and its meaning." else null
        NoteKind.KANJI -> if (f[Fields.KANJI] == null || f[Fields.MEANING] == null) "Add the kanji and its meaning." else null
        NoteKind.GRAMMAR -> if (f[Fields.PATTERN] == null || f[Fields.MEANING] == null) "Add the pattern and its meaning." else null
        NoteKind.BASIC, NoteKind.BASIC_AND_REVERSED -> if (f[Fields.FRONT] == null || f[Fields.BACK] == null) "Add both sides." else null
        NoteKind.CLOZE -> if (Cloze.numbers(f[Fields.TEXT].orEmpty()).isEmpty()) "Mark at least one part to hide, like {{c1::食べる}}." else null
    }
}
