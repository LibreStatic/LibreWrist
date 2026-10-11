package io.openshm.patcher.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import io.openshm.patcher.R
import io.openshm.patcher.ui.theme.LocalReducedMotion

/**
 * The guided setup, in the order the app walks through it. Each step owns one Material shape
 * (canvas "Expressive language" board). [Verify] has no screen of its own in v1: file checks run
 * inline in Acquire, so its Gem identifies the Galaxy Wearable pairing check instead.
 */
enum class SetupStep(val number: Int, val onWatch: Boolean = false) {
    Profile(1),
    WearablePairing(2),
    WatchPair(3, onWatch = true),
    Acquire(4),
    Customize(5),
    Build(6),
    InstallPhone(7),
    WatchInstall(8, onWatch = true),
    SelfTest(9),
    ;

    companion object {
        val Total: Int = entries.size
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun SetupStep.polygon(): RoundedPolygon = when (this) {
    SetupStep.Profile -> MaterialShapes.Circle
    SetupStep.WearablePairing -> MaterialShapes.Gem
    SetupStep.WatchPair -> MaterialShapes.Clover4Leaf
    SetupStep.Acquire -> MaterialShapes.Arrow
    SetupStep.Customize -> MaterialShapes.Flower
    SetupStep.Build -> MaterialShapes.Cookie9Sided
    SetupStep.InstallPhone -> MaterialShapes.Pill
    SetupStep.WatchInstall -> MaterialShapes.Clover4Leaf
    SetupStep.SelfTest -> MaterialShapes.Sunny
}

/** Remembers the shape of the step the user came from, so the next header can morph out of it. */
internal object StepShapeMemory {
    var last: SetupStep? = null
}

/** Icon drawn on the step shape. */
internal fun SetupStep.icon(): ImageVector = when (this) {
    SetupStep.Profile -> Icons.Rounded.Watch
    SetupStep.WearablePairing -> Icons.Rounded.Bluetooth
    SetupStep.WatchPair -> Icons.Rounded.Link
    SetupStep.Acquire -> Icons.Rounded.Download
    SetupStep.Customize -> Icons.Rounded.Tune
    SetupStep.Build -> Icons.Rounded.Build
    SetupStep.InstallPhone -> Icons.Rounded.InstallMobile
    SetupStep.WatchInstall -> Icons.Rounded.Watch
    SetupStep.SelfTest -> Icons.Rounded.Checklist
}

@Composable
private fun SetupStep.contentColor(): Color =
    if (onWatch) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer

@Composable
private fun SetupStep.containerColor(): Color =
    if (onWatch) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer

/**
 * Step shape that morphs from the previous step's shape with a spatial spring. With reduced motion
 * (animator duration scale 0) the morph becomes a crossfade between the two shapes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StepShape(step: SetupStep, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        StepShapeBackground(step)
        Icon(step.icon(), contentDescription = null, tint = step.contentColor(), modifier = Modifier.size(22.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepShapeBackground(step: SetupStep, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val from = remember { StepShapeMemory.last ?: step }
    val toColor = step.containerColor()
    val fromColor = from.containerColor()
    val size = Modifier.size(44.dp)

    if (reducedMotion) {
        var shown by remember { mutableStateOf(from) }
        LaunchedEffect(step) {
            shown = step
            StepShapeMemory.last = step
        }
        Crossfade(targetState = shown, modifier = modifier.then(size), label = "stepShape") { target ->
            Spacer(
                Modifier
                    .then(size)
                    .clip(PolygonShape(target.polygon()))
                    .background(target.containerColor()),
            )
        }
    } else {
        val progress = remember { Animatable(if (from == step) 1f else 0f) }
        val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
        LaunchedEffect(step) {
            progress.animateTo(1f, spec)
            StepShapeMemory.last = step
        }
        val morph = remember(from, step) { Morph(from.polygon(), step.polygon()) }
        Spacer(
            modifier
                .then(size)
                .clip(MorphShape(morph) { progress.value })
                .background(lerp(fromColor, toColor, progress.value.coerceIn(0f, 1f))),
        )
    }
}

/** "STEP N OF M" plus the screen title, next to the morphing step shape. */
@Composable
fun StepHeader(step: SetupStep, title: String, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.step_label, step.number, SetupStep.Total)
    val watchLabel = if (step.onWatch) stringResource(R.string.step_label_watch_suffix) else ""
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepShape(step, Modifier.clearAndSetSemantics { })
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label + watchLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}
