package app.nihongoflow.core.anki

import app.nihongoflow.core.study.CollectionRepository
import app.nihongoflow.core.study.ContentSource
import app.nihongoflow.core.study.ImportRepository
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.NoteRules
import app.nihongoflow.core.study.SourceKind
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * A parsed .csv or .txt file, including Anki's text export with its `#separator:`,
 * `#html:`, `#columns:` and `#tags column:` header lines.
 */
data class DelimitedText(
    val separator: Char,
    val html: Boolean,
    val columns: List<String>,
    val rows: List<List<String>>,
    /** 0-based column holding space-separated tags, if the file says so. */
    val tagsColumn: Int?,
) {
    companion object {
        private val separators = mapOf(
            "tab" to '\t', "comma" to ',', "semicolon" to ';', "pipe" to '|', "colon" to ':', "space" to ' ',
        )

        fun parse(input: String): DelimitedText {
            val text = input.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            var separator: Char? = null
            var html: Boolean? = null
            var columns: List<String>? = null
            var tagsColumn: Int? = null
            val headerless = StringBuilder()
            var inHeader = true
            for (line in text.split('\n')) {
                if (inHeader && line.startsWith("#") && ':' in line) {
                    val name = line.substring(1).substringBefore(':').trim().lowercase()
                    val value = line.substringAfter(':').trim()
                    when (name) {
                        "separator" -> separator = separators[value.lowercase()] ?: value.firstOrNull()
                        "html" -> html = value.equals("true", ignoreCase = true)
                        "columns" -> columns = value.split(separator ?: '\t').map { it.trim() }
                        "tags column" -> tagsColumn = value.toIntOrNull()?.minus(1)
                    }
                    continue
                }
                inHeader = false
                headerless.append(line).append('\n')
            }
            val body = headerless.toString()
            val sep = separator ?: guessSeparator(body)
            if (columns != null && separator == null) columns = columns!!.flatMap { it.split(sep) }.map { it.trim() }
            val rows = parseRows(body, sep).filter { r -> r.any { it.isNotBlank() } }
            val width = maxOf(rows.maxOfOrNull { it.size } ?: 0, columns?.size ?: 0)
            val names = (0 until width).map { i -> columns?.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Column ${i + 1}" }
            val looksHtml = html ?: rows.take(50).any { r -> r.any { Regex("(?i)<(br|div|b|i|span|img)\\b").containsMatchIn(it) } }
            return DelimitedText(sep, looksHtml, names, rows, tagsColumn)
        }

        private fun guessSeparator(body: String): Char {
            val sample = body.lineSequence().filter { it.isNotBlank() }.take(20).toList()
            if (sample.isEmpty()) return '\t'
            return listOf('\t', ';', ',', '|').maxByOrNull { c ->
                val counts = sample.map { line -> line.count { it == c } }
                if (counts.all { it == 0 }) -1 else counts.count { it == counts.first() && it > 0 } * 100 + counts.first()
            } ?: '\t'
        }

        /** RFC 4180 style: quotes around fields, doubled quotes inside, newlines allowed in quotes. */
        private fun parseRows(body: String, sep: Char): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            var fieldStart = true
            while (i < body.length) {
                val c = body[i]
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < body.length && body[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                    } else field.append(c)
                } else when {
                    c == '"' && fieldStart -> quoted = true
                    c == sep -> { row += field.toString(); field.clear(); fieldStart = true; i++; continue }
                    c == '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf(); fieldStart = true; i++; continue }
                    else -> field.append(c)
                }
                fieldStart = false
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
            return rows
        }
    }
}

data class TextPreview(val text: DelimitedText, val guess: NoteTypeMapping, val samples: List<List<String>>)

/** Imports .csv and .txt files into one deck. There's no review history in text, so cards start new. */
class TextImporter(
    private val collection: CollectionRepository,
    private val sink: ImportRepository,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun preview(text: DelimitedText): TextPreview {
        val kind = MappingGuesser.guessKind(false, text.columns)
        val fields = MappingGuesser.guessFields(kind, text.columns)
        return TextPreview(text, NoteTypeMapping(kind, fields), text.rows.take(3))
    }

    fun sample(row: List<String>, mapping: NoteTypeMapping, html: Boolean): Map<String, String> =
        FieldConverter.convert(row, mapping, html)

    suspend fun import(
        text: DelimitedText,
        mapping: NoteTypeMapping,
        deckName: String,
        duplicates: DuplicatePolicy,
        sourceName: String,
    ): ImportResult {
        val now = clock.now()
        var incomplete = 0
        val incoming = text.rows.mapNotNull { row ->
            val fields = FieldConverter.convert(row, mapping, text.html)
            if (NoteRules.problem(mapping.kind, fields) != null) { incomplete++; return@mapNotNull null }
            val tags = text.tagsColumn?.let { row.getOrNull(it) }.orEmpty().split(' ').filter { it.isNotBlank() }.toSet()
            IncomingNote(null, mapping.kind, fields, tags, deckName.trim().ifBlank { sourceName }, createdAt = null)
        }
        val source = ContentSource(SourceKind.ANKI_IMPORT, sourceName.ifBlank { "Text file" })
        return ImportWriter(collection, sink, now, newId, source)
            .write(incoming, duplicates, ProgressMode.FRESH_START, scheduler = null, skippedByChoice = 0, incomplete = incomplete, copyMedia = null)
    }

    /** Kinds a text file can become. Cloze works too when the text has `{{c1::…}}` marks. */
    val kinds: List<NoteKind> = NoteKind.entries
}
