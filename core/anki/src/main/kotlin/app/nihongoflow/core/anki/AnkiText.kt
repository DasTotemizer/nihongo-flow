package app.nihongoflow.core.anki

import app.nihongoflow.core.study.JapaneseText

/**
 * Turns Anki field HTML into the plain text Nihongo Flow stores. Nothing from an imported
 * file is ever run or rendered as HTML: tags are dropped, line breaks kept, entities
 * decoded, `<img>` becomes an `[image:…]` reference and `[sound:…]` stays as it is.
 */
object AnkiText {

    private val blockBreak = Regex("""(?i)<\s*(br|/div|/p|/li|/tr|/h[1-6])\b[^>]*>""")
    private val image = Regex("""(?i)<\s*img\b[^>]*?\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))[^>]*>""")
    private val scriptOrStyle = Regex("""(?is)<\s*(script|style)\b.*?<\s*/\s*\1\s*>""")
    private val tag = Regex("""<[^>]*>""")
    private val entity = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
    private val namedEntities = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "ensp" to " ", "emsp" to " ", "thinsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·", "times" to "×",
    )

    fun toPlain(html: String): String {
        if (html.isEmpty()) return ""
        var s = scriptOrStyle.replace(html, "")
        s = image.replace(s) { m ->
            val src = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            val name = decodeEntities(src).substringAfterLast('/')
            if (AnkiPackageReader.safeName(name)) "[image:$name]" else ""
        }
        s = blockBreak.replace(s, "\n")
        s = tag.replace(s, "")
        s = decodeEntities(s)
        return s.replace(' ', ' ')
            .lines()
            .map { it.replace(Regex("[ \t]+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun decodeEntities(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
            else -> namedEntities[e.lowercase()] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    private val furigana = Regex("""\[([^\[\]]*)]""")

    /** True when text uses Anki's furigana syntax, like `食[た]べる`. */
    fun hasFurigana(text: String): Boolean =
        furigana.findAll(text).any { m -> m.groupValues[1].isNotEmpty() && !m.value.startsWith("[sound:") && !m.value.startsWith("[image:") &&
            m.groupValues[1].all { JapaneseText.isKana(it) || it == 'ー' || it == '・' } }

    /**
     * Reading in kana from Anki furigana: `食[た]べる` → `たべる`, `日本語[にほんご]を 話[はな]す`
     * → `にほんごをはなす`. Each bracket replaces the run of kanji just before it.
     */
    fun furiganaToKana(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '[') {
                val close = text.indexOf(']', i)
                if (close > i) {
                    val reading = text.substring(i + 1, close)
                    // Drop the kanji run we already copied, then write the reading instead.
                    var k = out.length
                    while (k > 0 && JapaneseText.isKanji(out[k - 1])) k--
                    out.setLength(k)
                    out.append(reading)
                    i = close + 1
                    continue
                }
            }
            if (c != ' ') out.append(c)
            i++
        }
        return out.toString()
    }

    /** The written form from Anki furigana: `食[た]べる` → `食べる`. */
    fun furiganaToKanji(text: String): String =
        furigana.replace(text) { m -> if (m.value.startsWith("[sound:") || m.value.startsWith("[image:")) m.value else "" }
            .replace(" ", "")
}
