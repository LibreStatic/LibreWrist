package io.openshm.patcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.openshm.patcher.ui.PatcherApp

/** The only activity: edge to edge, with Navigation Compose inside [PatcherApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PatcherApp() }
    }
}
