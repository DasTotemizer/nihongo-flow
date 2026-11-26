package app.nihongoflow.core.study

import java.text.Normalizer

/**
 * Normalisation used for duplicate detection. Compares whole written forms and readings,
 * never single kanji, so 食べる and 食事 are different items even though they share 食.
 */
object JapaneseText {

    /** NFKC (full-width ASCII and half-width kana become standard forms), trimmed, no inner spaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("""\s+"""), "")
            .trim()

    /** As [normalize], with katakana folded to hiragana, for comparing readings. */
    fun normalizeReading(text: String): String = katakanaToHiragana(normalize(text))

    fun katakanaToHiragana(text: String): String = buildString(text.length) {
        for (c in text) {
            // ァ (U+30A1) .. ヶ (U+30F6) map onto ぁ (U+3041) .. ゖ (U+3096).
            append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
        }
    }

    fun containsKanji(text: String): Boolean = text.any { isKanji(it) }

    /** Hiragana or katakana, including the long-vowel mark. */
    fun isKana(c: Char): Boolean = c in '\u3041'..'\u309F' || c in '\u30A0'..'\u30FF'

    fun isKanji(c: Char): Boolean =
        c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

    /** Key identifying a vocabulary item: written form plus reading. */
    fun vocabularyKey(word: String, reading: String): String =
        normalize(word) + "|" + normalizeReading(reading.ifBlank { word })
}
