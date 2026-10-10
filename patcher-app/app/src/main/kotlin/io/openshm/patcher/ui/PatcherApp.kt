package io.openshm.patcher.ui

import androidx.compose.runtime.Composable
import io.openshm.patcher.ui.nav.PatcherNavHost
import io.openshm.patcher.ui.theme.PatcherTheme

@Composable
fun PatcherApp() {
    PatcherTheme {
        PatcherNavHost()
    }
}
