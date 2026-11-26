package app.nihongoflow.ui.copy

import kotlin.test.Test
import kotlin.test.assertTrue

/** The app must never use guilt, shame, broken streaks or alarming backlog wording. */
class GreetingCopyTest {

    private val banned = listOf(
        "fail", "streak", "behind", "must", "missed", "overdue", "backlog", "catch up", "late", "lost",
    )

    @Test
    fun `no fixed copy uses guilt-based wording`() {
        for (text in GreetingCopy.allFixedStrings) {
            val lower = text.lowercase()
            for (word in banned) {
                assertTrue(word !in lower, "\"$text\" contains \"$word\"")
            }
        }
    }
}
