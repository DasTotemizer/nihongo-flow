package app.nihongoflow.data

import androidx.test.core.app.ApplicationProvider
import app.nihongoflow.core.study.StudyStoreContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the shared StudyStore contract against Room on a real (in-memory) SQLite database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomStudyStoreTest {

    private fun check(name: String) {
        val db = NihongoFlowDatabase.inMemory(ApplicationProvider.getApplicationContext())
        try {
            StudyStoreContract.all.getValue(name)(RoomStudyStore(db))
        } finally {
            db.close()
        }
    }

    @Test fun decks() = check("decks are stored, renamed and listed by name")
    @Test fun cards() = check("cards start new and keep their introduction order")
    @Test fun suspension() = check("suspended cards are never scheduled")
    @Test fun review() = check("a review updates memory, appends history and clears deferral")
    @Test fun deleteDeck() = check("deleting a deck removes its cards and their history")
    @Test fun bookkeeping() = check("bookkeeping round-trips")
    @Test fun summaries() = check("deck summaries count new and due cards")

    @Test
    fun everyContractCheckIsCovered() {
        kotlin.test.assertEquals(7, StudyStoreContract.all.size, "add a test above for each new contract check")
    }
}
