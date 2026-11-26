package app.nihongoflow.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.nihongoflow.core.anki.AnkiImportException
import app.nihongoflow.core.anki.AnkiImporter
import app.nihongoflow.core.anki.AnkiPackage
import app.nihongoflow.core.anki.AnkiPackageReader
import app.nihongoflow.core.anki.DelimitedText
import app.nihongoflow.core.anki.TextImporter
import app.nihongoflow.core.study.ImportRepository
import app.nihongoflow.core.study.CollectionRepository
import app.nihongoflow.core.study.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A file the learner picked, opened and ready to preview. */
sealed interface OpenedImport {
    val fileName: String

    class Anki(override val fileName: String, val pkg: AnkiPackage) : OpenedImport
    class Text(override val fileName: String, val text: DelimitedText) : OpenedImport
}

/**
 * Opens files for import. The file is copied into the app's cache first (the picker's
 * permission may not last), then read on a background thread.
 */
class ImportService(
    private val context: Context,
    collection: CollectionRepository,
    progress: ProgressRepository,
    sink: ImportRepository,
) {
    val anki = AnkiImporter(collection, progress, sink)
    val text = TextImporter(collection, sink)
    val mediaDir: File get() = File(context.filesDir, "media")
    private val workDir: File get() = File(context.cacheDir, "import")

    suspend fun open(uri: Uri): OpenedImport = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "Imported file"
        val dir = workDir.apply { mkdirs() }
        dir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 24 * 3600_000L }?.forEach { it.delete() }
        val copy = File(dir, UUID.randomUUID().toString())
        val input = context.contentResolver.openInputStream(uri) ?: throw AnkiImportException("That file couldn't be opened.")
        input.use { inp -> copy.outputStream().use { inp.copyTo(it) } }
        try {
            val head = copy.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            if (isZip) {
                OpenedImport.Anki(name, AnkiPackageReader(AndroidSql.opener, dir).read(copy))
            } else {
                if (copy.length() > 50L * 1024 * 1024) throw AnkiImportException("That text file is too large to import.")
                OpenedImport.Text(name, DelimitedText.parse(copy.readText(Charsets.UTF_8)))
            }
        } finally {
            // An opened package keeps its own handle on the zip, so the name can go now.
            copy.delete()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
