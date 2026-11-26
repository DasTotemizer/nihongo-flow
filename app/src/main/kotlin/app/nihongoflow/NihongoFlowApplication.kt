package app.nihongoflow

import android.app.Application
import app.nihongoflow.core.scheduler.FsrsScheduler
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.data.NihongoFlowDatabase
import app.nihongoflow.data.RoomStudyStore

/**
 * Holds the app's single objects. Plain constructor wiring: the graph is small, and this
 * keeps the build free of an annotation-processing DI framework until one is needed.
 */
class NihongoFlowApplication : Application() {
    val database: NihongoFlowDatabase by lazy { NihongoFlowDatabase.open(this) }
    val study: StudyService by lazy { StudyService(RoomStudyStore(database), FsrsScheduler()) }
}
