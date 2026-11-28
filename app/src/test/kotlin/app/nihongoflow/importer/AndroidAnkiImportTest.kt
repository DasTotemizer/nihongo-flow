package app.nihongoflow.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.nihongoflow.core.anki.AnkiImportOptions
import app.nihongoflow.core.anki.AnkiImporter
import app.nihongoflow.core.anki.AnkiPackageReader
import app.nihongoflow.core.anki.ProgressMode
import app.nihongoflow.core.model.ReviewSource
import app.nihongoflow.core.study.Fields
import app.nihongoflow.data.NihongoFlowDatabase
import app.nihongoflow.data.RoomStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads real Anki packages through Android's own SQLite, which (like a phone) has no
 * `unicase` collation, and imports them into the Room database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAnkiImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "anki-import-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, name)
        javaClass.getResourceAsStream("/anki/$name")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    @Test
    fun `current and legacy packages import with their history into Room`() = runBlocking {
        for (name in listOf("modern.apkg", "legacy.apkg")) {
            context.deleteDatabase(dbName)
            val db = NihongoFlowDatabase.open(context, dbName)
            val store = RoomStore(db)
            val importer = AnkiImporter(store, store, store)
            val result = AnkiPackageReader(AndroidSql.opener, File(context.cacheDir, "work")).read(fixture(name)).use { pkg ->
                val preview = importer.preview(pkg.collection)
                assertEquals(4, preview.noteTypes.size, name)
                importer.import(pkg, AnkiImportOptions(preview.noteTypes.associate { it.noteType.id to it.guess }, ProgressMode.KEEP_PROGRESS), File(context.filesDir, "media"), name)
            }
            assertEquals(6, result.notesAdded, name)
            assertEquals(6, result.reviewsImported, name)

            val taberu = store.allNotes().single { it.field(Fields.WORD) == "食べる" }
            assertEquals("たべる", taberu.field(Fields.READING))
            val card = store.cards(taberu.id).single()
            val history = store.reviewsFor(card.id)
            assertEquals(5, history.size)
            assertTrue(history.all { it.source == ReviewSource.ANKI_IMPORT })
            assertEquals(history.last().stateAfter, assertNotNull(store.memory(card.id)))
            assertEquals(setOf("Japanese::Vocab", "Misc"), store.decks().map { it.name }.toSet())
            assertEquals("ID3fake-audio", File(context.filesDir, "media/taberu.mp3").readText())
            db.close()
        }
    }
}
