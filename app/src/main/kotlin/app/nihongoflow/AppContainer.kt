package app.nihongoflow

import android.content.Context
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.data.NihongoFlowDatabase
import app.nihongoflow.data.RoomStore

/**
 * Manual dependency container. One database and one StudyService for the whole app.
 * (A DI framework can replace this later without touching screens, which only see
 * StudyService.)
 */
class AppContainer(context: Context) {
    val database: NihongoFlowDatabase = NihongoFlowDatabase.open(context)
    private val store = RoomStore(database)
    val study: StudyService = StudyService(collection = store, progress = store)
}
