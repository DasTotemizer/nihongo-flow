package app.nihongoflow.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.nihongoflow.core.study.StudyService
import app.nihongoflow.ui.LocalStudyService

/**
 * Loads something from the study service and reloads it whenever anything is written, or
 * when one of [keys] changes. Returns null until the first load finishes; later reloads
 * keep showing the previous value, so screens do not flicker.
 */
@Composable
fun <T> rememberStudyData(vararg keys: Any?, load: suspend StudyService.() -> T): T? {
    val service = LocalStudyService.current
    val version by service.changes.collectAsState()
    var value by remember(*keys) { mutableStateOf<T?>(null) }
    LaunchedEffect(version, *keys) { value = service.load() }
    return value
}
