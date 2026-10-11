package io.openshm.patcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.openshm.patcher.ui.components.TimeBackdrop
import io.openshm.patcher.ui.nav.BackdropPolicy
import io.openshm.patcher.ui.nav.PatcherNavHost
import io.openshm.patcher.ui.nav.SetupFlow
import io.openshm.patcher.ui.theme.PatcherTheme

/** Widest the content column gets; on tablets and unfolded foldables the rest is side margin. */
private val MaxContentWidth = 640.dp

@Composable
fun PatcherApp() {
    PatcherTheme {
        val navController = rememberNavController()
        val entry by navController.currentBackStackEntryAsState()
        var backdrop by rememberSaveable(stateSaver = BackdropPolicy.Saver) { mutableStateOf(BackdropPolicy()) }
        // The backdrop's rail follows the step in the setup flow; side screens keep the last step.
        var railStep by rememberSaveable { mutableIntStateOf(0) }
        var railBeat by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(entry?.id) {
            val current = entry ?: return@LaunchedEffect
            backdrop = backdrop.onDestination(current.destination.route, current.id)
            val step = SetupFlow.order.indexOfFirst { it.path == current.destination.route }
            if (step >= 0) railStep = step
            railBeat++
        }
        // Surface also sets the default content color, so text without an explicit color follows the theme.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // Lambdas, read while drawing: page changes never recompose the backdrop.
            TimeBackdrop(
                dispersed = backdrop.dispersed,
                progress = { railStep.toFloat() },
                beat = { railBeat },
            )
            Box(contentAlignment = Alignment.TopCenter) {
                PatcherNavHost(
                    Modifier.widthIn(max = MaxContentWidth).fillMaxWidth().fillMaxHeight(),
                    navController = navController,
                )
            }
        }
    }
}
