package io.openshm.patcher.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.openshm.patcher.ui.components.UnattendedPhase
import io.openshm.patcher.ui.components.UnattendedRunOverlay
import io.openshm.patcher.ui.components.unattendedSaturation

/** A step screen at half saturation with the centered intro, as PatcherNavHost layers them. */
@Composable
fun UnattendedIntroDriver() = PreviewHost(ColorMode.Fallback) { UnattendedStep(UnattendedPhase.Intro) }

/** The same step with the intro collapsed into the corner badge. */
@Composable
fun UnattendedBadgeDriver() = PreviewHost(ColorMode.Fallback) { UnattendedStep(UnattendedPhase.Badge) }

/** The step waits for the user: the "I need your attention" dialog, badge hidden. */
@Composable
fun UnattendedAttentionDriver() = PreviewHost(ColorMode.Fallback) { UnattendedStep(UnattendedPhase.Attention) }

/** The dialog was dismissed: the red paused badge, which reopens it. */
@Composable
fun UnattendedPausedDriver() = PreviewHost(ColorMode.Fallback) { UnattendedStep(UnattendedPhase.Paused) }

@Composable
private fun UnattendedStep(phase: UnattendedPhase) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().unattendedSaturation(phase)) { AcquireSample() }
        UnattendedRunOverlay(phase)
    }
}
