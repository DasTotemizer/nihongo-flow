package app.nihongoflow.core.anki

import com.github.luben.zstd.ZstdInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * An opened Anki package (.apkg or .colpkg). Holds the zip open so media can be copied
 * after the learner confirms the import. Close it when done.
 */
class AnkiPackage internal constructor(
    val collection: AnkiCollection,
    private val zip: ZipFile,
    private val workFile: File,
) : Closeable {

    private val mediaByName = collection.media.associateBy { it.name }

    /** Copies one media file to [target]. Returns false if the package doesn't have it. */
    fun copyMedia(name: String, target: File): Boolean {
        val entry = mediaByName[name] ?: return false
        val zipEntry = zip.getEntry(entry.zipEntry) ?: return false
        target.parentFile?.mkdirs()
        zip.getInputStream(zipEntry).use { raw ->
            val input = if (entry.zstdCompressed) ZstdInputStream(raw) else raw
            input.use { inp -> target.outputStream().use { inp.copyTo(it) } }
        }
        return true
    }

    override fun close() {
        runCatching { zip.close() }
        workFile.delete()
    }
}

/**
 * Reads Anki packages in all three formats Anki has written: `collection.anki2`,
 * `collection.anki21` (schema 11, JSON note types) and `collection.anki21b` (schema 18,
 * zstd-compressed, note types in their own tables, protobuf blobs).
 *
 * The collection is copied to [workDir] first and only ever opened read-only.
 */
class AnkiPackageReader(private val opener: SqlOpener, private val workDir: File) {

    fun read(packageFile: File): AnkiPackage {
        val zip = try {
            ZipFile(packageFile)
        } catch (e: ZipException) {
            throw AnkiImportException("This doesn't look like an Anki package (.apkg or .colpkg).", e)
        }
        try {
            val (entryName, format) = listOf(
                "collection.anki21b" to AnkiFormat.ANKI21B,
                "collection.anki21" to AnkiFormat.ANKI21,
                "collection.anki2" to AnkiFormat.ANKI2,
            ).firstOrNull { zip.getEntry(it.first) != null }
                ?: throw AnkiImportException("This file has no Anki collection inside.")

            workDir.mkdirs()
            val dbFile = File.createTempFile("anki-import", ".db", workDir)
            zip.getInputStream(zip.getEntry(entryName)).use { raw ->
                val input: InputStream = if (format == AnkiFormat.ANKI21B) ZstdInputStream(raw) else raw
                input.use { inp -> CollationPatch(dbFile.outputStream().buffered()).use { inp.copyTo(it) } }
            }
            useRollbackJournal(dbFile)
            val collection = opener.open(dbFile).use { db -> readCollection(db, format, readMedia(zip)) }
            return AnkiPackage(collection, zip, dbFile)
        } catch (e: AnkiImportException) {
            zip.close()
            throw e
        } catch (e: Exception) {
            zip.close()
            throw AnkiImportException("This Anki file couldn't be read. It may be damaged or from an unsupported version.", e)
        }
    }

    private fun readCollection(db: SqlDatabase, format: AnkiFormat, media: List<AnkiMediaEntry>): AnkiCollection {
        val modern = db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'notetypes'") { it.long(0) }
            .first() > 0
        val noteTypes = if (modern) modernNoteTypes(db) else legacyNoteTypes(db)
        val decks = if (modern) modernDecks(db) else legacyDecks(db)
        val notes = db.query("SELECT id, guid, mid, tags, flds FROM notes ORDER BY id") { r ->
            AnkiNote(
                id = r.long(0),
                guid = r.string(1).orEmpty(),
                noteTypeId = r.long(2),
                tags = r.string(3).orEmpty().split(' ').filter { it.isNotBlank() },
                fields = r.string(4).orEmpty().split('\u001f'),
            )
        }
        val cards = db.query("SELECT id, nid, did, ord, type, queue, due, odid FROM cards ORDER BY id") { r ->
            val odid = r.long(7)
            AnkiCard(
                id = r.long(0),
                noteId = r.long(1),
                deckId = if (odid != 0L) odid else r.long(2),
                ord = r.long(3).toInt(),
                type = r.long(4).toInt(),
                queue = r.long(5).toInt(),
                due = r.long(6),
            )
        }
        val reviews = db.query("SELECT id, cid, ease, ivl, type, time FROM revlog ORDER BY cid, id") { r ->
            AnkiReview(r.long(0), r.long(1), r.long(2).toInt(), r.long(3).toInt(), r.long(4).toInt(), r.long(5))
        }
        return AnkiCollection(format, noteTypes, decks, notes, cards, reviews, media)
    }

    // ---- Schema 18 (collection.anki21b) ----

    private fun modernNoteTypes(db: SqlDatabase): List<AnkiNoteType> {
        val fields = db.query("SELECT ntid, ord, name FROM fields") { Triple(it.long(0), it.long(1).toInt(), it.string(2).orEmpty()) }
            .groupBy { it.first }
        val templates = db.query("SELECT ntid, ord, name, config FROM templates") { r ->
            val config = Proto(r.blob(3) ?: ByteArray(0))
            r.long(0) to AnkiTemplate(r.long(1).toInt(), r.string(2).orEmpty(), config.string(1).orEmpty(), config.string(2).orEmpty())
        }.groupBy({ it.first }, { it.second })
        return db.query("SELECT id, name, config FROM notetypes ORDER BY id") { r ->
            val id = r.long(0)
            AnkiNoteType(
                id = id,
                name = r.string(1).orEmpty(),
                isCloze = Proto(r.blob(2) ?: ByteArray(0)).varint(1) == 1L,
                fields = fields[id].orEmpty().sortedBy { it.second }.map { it.third },
                templates = templates[id].orEmpty().sortedBy { it.ord },
            )
        }
    }

    private fun modernDecks(db: SqlDatabase): List<AnkiDeck> =
        db.query("SELECT id, name, kind FROM decks") { r ->
            // DeckKind is a oneof: field 1 normal, field 2 filtered.
            AnkiDeck(r.long(0), r.string(1).orEmpty().replace("\u001f", "::"), Proto(r.blob(2) ?: ByteArray(0)).has(2))
        }

    // ---- Schema 11 (collection.anki2 and collection.anki21) ----

    private fun colJson(db: SqlDatabase, column: String): JsonObject {
        val text = db.query("SELECT $column FROM col") { it.string(0) }.firstOrNull().orEmpty()
        if (text.isBlank()) return JsonObject(emptyMap())
        return json.parseToJsonElement(text).jsonObject
    }

    private fun legacyNoteTypes(db: SqlDatabase): List<AnkiNoteType> =
        colJson(db, "models").values.map { it.jsonObject }.map { m ->
            AnkiNoteType(
                id = m["id"]!!.jsonPrimitive.long,
                name = m["name"]?.jsonPrimitive?.content.orEmpty(),
                isCloze = m["type"]?.jsonPrimitive?.intOrNull == 1,
                fields = (m["flds"] as? JsonArray).orEmpty().map { it.jsonObject }
                    .sortedBy { it["ord"]?.jsonPrimitive?.intOrNull ?: 0 }
                    .map { it["name"]?.jsonPrimitive?.content.orEmpty() },
                templates = (m["tmpls"] as? JsonArray).orEmpty().map { it.jsonObject }.map { t ->
                    AnkiTemplate(
                        ord = t["ord"]?.jsonPrimitive?.int ?: 0,
                        name = t["name"]?.jsonPrimitive?.content.orEmpty(),
                        front = t["qfmt"]?.jsonPrimitive?.content.orEmpty(),
                        back = t["afmt"]?.jsonPrimitive?.content.orEmpty(),
                    )
                }.sortedBy { it.ord },
            )
        }.sortedBy { it.id }

    private fun legacyDecks(db: SqlDatabase): List<AnkiDeck> =
        colJson(db, "decks").values.map { it.jsonObject }.map { d ->
            AnkiDeck(
                id = d["id"]!!.jsonPrimitive.long,
                name = d["name"]?.jsonPrimitive?.content.orEmpty(),
                filtered = (d["dyn"]?.jsonPrimitive?.longOrNull ?: 0L) != 0L,
            )
        }

    // ---- Media list ----

    private fun readMedia(zip: ZipFile): List<AnkiMediaEntry> {
        val entry = zip.getEntry("media") ?: return emptyList()
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        if (bytes.isEmpty()) return emptyList()
        val zstd = bytes.size >= 4 && bytes[0] == 0x28.toByte() && bytes[1] == 0xB5.toByte() &&
            bytes[2] == 0x2F.toByte() && bytes[3] == 0xFD.toByte()
        return if (zstd) {
            // Current format: zstd-compressed MediaEntries { repeated MediaEntry entries = 1 },
            // MediaEntry { name = 1; size = 2; sha1 = 3; legacy_zip_filename = 4 }.
            // Each file sits in the zip under its index, itself zstd-compressed.
            val proto = Proto(ZstdInputStream(bytes.inputStream()).use { it.readBytes() })
            proto.messages(1).mapIndexedNotNull { index, e ->
                val name = e.string(1)?.takeIf { safeName(it) } ?: return@mapIndexedNotNull null
                val zipName = e.varint(4)?.toString() ?: index.toString()
                AnkiMediaEntry(name, zipName, zstdCompressed = true)
            }
        } else {
            // Older format: JSON {"0": "file.mp3", ...}.
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.mapNotNull { (key, value) ->
                val name = value.jsonPrimitive.content
                if (safeName(name)) AnkiMediaEntry(name, key, zstdCompressed = false) else null
            }
        }
    }

    /** A WAL-mode file can't be opened read-only without its side files; switch the header to the classic journal. */
    private fun useRollbackJournal(file: File) {
        java.io.RandomAccessFile(file, "rw").use { f ->
            if (f.length() < 100) return
            f.seek(18)
            val write = f.read()
            val read = f.read()
            if (write == 2 || read == 2) {
                f.seek(18)
                f.write(1)
                f.write(1)
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Media names come from the file, so refuse anything that could escape the media folder. */
        fun safeName(name: String): Boolean =
            name.isNotBlank() && name.length <= 255 && '/' !in name && '\\' !in name && name != "." && name != ".." &&
                name.none { it.code < 32 }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

/**
 * Current Anki declares its own `unicase` collation in the schema. Platform SQLite does not
 * have it and refuses to read those tables. On our private copy, the declaration is swapped
 * for the built-in NOCASE of exactly the same length, so the file stays valid. The importer
 * only scans whole tables, so collation order never matters.
 */
internal class CollationPatch(out: OutputStream) : FilterOutputStream(out) {
    private val patterns = listOf("COLLATE unicase", "collate unicase").map { it.toByteArray() }
    private val replacements = listOf("COLLATE NOCASE ", "collate NOCASE ").map { it.toByteArray() }
    private val window = ByteArray(patterns[0].size)
    private var filled = 0

    override fun write(b: Int) {
        window[filled++] = b.toByte()
        // Emit bytes until the window could still be the start of a pattern.
        while (filled > 0) {
            val match = patterns.indexOfFirst { p -> (0 until filled).all { window[it] == p[it] } }
            if (match < 0) {
                out.write(window[0].toInt())
                System.arraycopy(window, 1, window, 0, filled - 1)
                filled--
                continue
            }
            if (filled == window.size) {
                out.write(replacements[match])
                filled = 0
            }
            return
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            if (filled == 0) {
                // Fast path: copy straight through up to the next possible pattern start.
                var j = i
                while (j < end && b[j] != 'C'.code.toByte() && b[j] != 'c'.code.toByte()) j++
                if (j > i) out.write(b, i, j - i)
                i = j
                if (i >= end) break
            }
            write(b[i].toInt())
            i++
        }
    }

    override fun close() {
        if (filled > 0) out.write(window, 0, filled)
        filled = 0
        super.close()
    }
}
