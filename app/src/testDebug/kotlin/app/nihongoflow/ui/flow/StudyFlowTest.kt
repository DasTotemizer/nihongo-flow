package app.nihongoflow.ui.flow

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import app.nihongoflow.core.scheduler.FsrsScheduler
import app.nihongoflow.core.study.InMemoryStudyStore
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.LocalStudyService
import app.nihongoflow.ui.NihongoFlowApp
import app.nihongoflow.ui.theme.NihongoFlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Walks through the main flow on a simulated phone: starter deck, a review, the answer
 * buttons, My Decks, Progress and Holiday Mode. Saves a screenshot of each step to
 * app/build/screenshots so the screens can be checked without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class StudyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val service = StudyService(InMemoryStudyStore(), FsrsScheduler())

    private fun shot(name: String) {
        val dir = System.getProperty("screenshot.dir") ?: return
        // captureToImage() waits for a frame that never comes under Robolectric, so draw the
        // window's view hierarchy directly instead.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(text: String) {
        try {
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("\"$text\" not shown. Tree:\n" + compose.onRoot().printToString(), e)
        }
    }

    @Test
    fun mainFlow() {
        compose.setContent {
            NihongoFlowTheme {
                CompositionLocalProvider(LocalStudyService provides service) { NihongoFlowApp() }
            }
        }
        waitFor("Add the starter deck")
        shot("01-home-empty")
        compose.onNodeWithText("Add the starter deck").performClick()
        waitFor("Start session")
        shot("02-home-ready")

        compose.onNodeWithText("Start session").performClick()
        waitFor("Show answer")
        shot("03-review-front")
        compose.onNodeWithText("Show answer").performClick()
        waitFor("Again")
        shot("04-review-answer")
        compose.onNodeWithText("Good").performClick()
        waitFor("Show answer")

        compose.onNodeWithText("My Decks").performClick()
        waitFor("Starter words")
        shot("05-decks")
        compose.onNodeWithText("Starter words").performClick()
        waitFor("Add card")
        shot("06-deck-detail")
        compose.onNodeWithText("Add card").performClick()
        waitFor("Front (Japanese)")
        shot("07-card-editor")

        compose.onNodeWithText("Progress").performClick()
        waitFor("Last 7 days")
        compose.onNodeWithText("Last 7 days").assertIsDisplayed()
        shot("08-progress")

        compose.onNodeWithText("Settings").performClick()
        waitFor("Holiday Mode")
        shot("09-holiday")
        compose.onNodeWithText("Start holiday").performClick()
        waitFor("I'm back, end it now")
        shot("10-holiday-on")
    }
}
