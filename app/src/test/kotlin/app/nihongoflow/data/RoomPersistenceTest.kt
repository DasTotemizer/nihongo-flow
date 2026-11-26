package app.nihongoflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.nihongoflow.core.holiday.HolidayMode
import app.nihongoflow.core.holiday.HolidayStatus
import app.nihongoflow.core.model.Rating
import app.nihongoflow.core.study.Fields
import app.nihongoflow.core.study.NoteKind
import app.nihongoflow.core.study.SaveNoteResult
import app.nihongoflow.core.study.StudyService
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** Learning data must survive closing and reopening the on-device database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "persistence-test.db"

    private class FixedClock(var now: Instant) : Clock {
        override fun now() = now
    }

    private val clock = FixedClock(Instant.parse("2026-05-04T18:00:00Z"))

    private fun service(db: NihongoFlowDatabase): StudyService {
        val store = RoomStore(db)
        return StudyService(store, store, clock, { TimeZone.UTC })
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
    }

    @Test
    fun `decks, cards, review history, settings and holidays survive a restart`() = runBlocking {
        var db = NihongoFlowDatabase.open(context, name)
        var study = service(db)

        val deck = study.createDeck("Words")
        val saved = assertIs<SaveNoteResult.Saved>(
            study.saveNote(
                deck.id, NoteKind.VOCABULARY,
                mapOf(Fields.WORD to "食べる", Fields.READING to "たべる", Fields.MEANING to "to eat"),
            ),
        )
        val session = study.startSession(study.plan())
        val first = assertNotNull(session.next(clock.now))
        study.answer(first, Rating.GOOD, 4_000)
        study.saveSettings(study.settings().copy(newCardsPerDay = 7))
        val today = study.today()
        study.scheduleHoliday(HolidayMode.CASUAL, today, today).getOrThrow()
        db.close()

        db = NihongoFlowDatabase.open(context, name)
        study = service(db)
        val store = RoomStore(db)

        assertEquals(listOf("Words"), study.decks().map { it.name })
        val note = assertNotNull(study.note(saved.note.id))
        assertEquals("食べる", note.field(Fields.WORD))
        assertEquals(2, store.cards(note.id).size)

        val history = store.reviewsFor(first)
        assertEquals(1, history.size)
        assertEquals(Rating.GOOD, history.single().rating)
        assertNotNull(store.memory(first))
        assertEquals(7, study.settings().newCardsPerDay)
        assertIs<HolidayStatus.Active>(study.holidayStatus())

        // Time moves on; the memory state is still there and the card becomes due again.
        clock.now = clock.now + 60.days
        assertTrue(store.dueCards(clock.now).any { it.card.id == first })
        db.close()
    }

    @Test
    fun `upgrading from version 1 keeps settings and turns on two-button answers`() = runBlocking {
        var db = NihongoFlowDatabase.open(context, name)
        var study = service(db)
        study.saveSettings(study.settings().copy(newCardsPerDay = 9, twoButtonReview = false))
        study.createDeck("Kept")

        // Rebuild the settings table exactly as schema version 1 had it, and mark the file as version 1.
        db.openHelper.writableDatabase.apply {
            execSQL(
                "CREATE TABLE user_settings_v1 (id INTEGER NOT NULL, desiredRetention REAL NOT NULL, " +
                    "targetSessionMinutes INTEGER NOT NULL, maxSessionMinutes INTEGER NOT NULL, " +
                    "maxReviewsPerSession INTEGER NOT NULL, newCardsPerDay INTEGER NOT NULL, " +
                    "recoveryPacing TEXT NOT NULL, preferredStudyDays TEXT NOT NULL, " +
                    "casualSessionMinutes INTEGER NOT NULL, dayStartHour INTEGER NOT NULL, " +
                    "enabledDirections TEXT NOT NULL, PRIMARY KEY(id))",
            )
            execSQL(
                "INSERT INTO user_settings_v1 SELECT id, desiredRetention, targetSessionMinutes, maxSessionMinutes, " +
                    "maxReviewsPerSession, newCardsPerDay, recoveryPacing, preferredStudyDays, casualSessionMinutes, " +
                    "dayStartHour, enabledDirections FROM user_settings",
            )
            execSQL("DROP TABLE user_settings")
            execSQL("ALTER TABLE user_settings_v1 RENAME TO user_settings")
            version = 1
        }
        db.close()

        db = NihongoFlowDatabase.open(context, name)
        study = service(db)
        val settings = study.settings()
        assertEquals(9, settings.newCardsPerDay)
        assertTrue(settings.twoButtonReview)
        assertEquals(listOf("Kept"), study.decks().map { it.name })
        db.close()
    }
}
