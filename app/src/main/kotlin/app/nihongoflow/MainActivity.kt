package app.nihongoflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nihongoflow.ui.NihongoFlowApp
import app.nihongoflow.ui.theme.NihongoFlowTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NihongoFlowApplication).container
        setContent {
            NihongoFlowTheme {
                NihongoFlowApp(container.study, container.imports)
            }
        }
    }
}
