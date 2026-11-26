package app.nihongoflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import app.nihongoflow.ui.LocalStudyService
import app.nihongoflow.ui.NihongoFlowApp
import app.nihongoflow.ui.theme.NihongoFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val study = (application as NihongoFlowApplication).study
        setContent {
            NihongoFlowTheme {
                CompositionLocalProvider(LocalStudyService provides study) {
                    NihongoFlowApp()
                }
            }
        }
    }
}
