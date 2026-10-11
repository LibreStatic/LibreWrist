package io.openshm.patcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openshm.patcher.ui.nav.PatcherNavHost
import io.openshm.patcher.ui.theme.PatcherTheme

/** Widest the content column gets; on tablets and unfolded foldables the rest is side margin. */
private val MaxContentWidth = 640.dp

@Composable
fun PatcherApp() {
    PatcherTheme {
        // Surface also sets the default content color, so text without an explicit color follows the theme.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(contentAlignment = Alignment.TopCenter) {
                PatcherNavHost(Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().fillMaxHeight())
            }
        }
    }
}
