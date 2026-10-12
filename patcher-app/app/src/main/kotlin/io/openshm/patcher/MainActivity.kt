package io.openshm.patcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.openshm.patcher.ui.PatcherApp
import io.openshm.patcher.update.AppUpdateRequests

/** The only activity: edge to edge, with Navigation Compose inside [PatcherApp]. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only a fresh launch carries the request; a recreation (rotation) must not repeat it.
        if (savedInstanceState == null) handle(intent)
        setContent { PatcherApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_UPDATER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_UPDATER)
            AppUpdateRequests.request()
        }
    }

    companion object {
        /** Set by the "new version" notification: open the update dialog. */
        const val EXTRA_OPEN_UPDATER = "io.openshm.patcher.extra.OPEN_UPDATER"
    }
}
