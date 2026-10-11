package io.openshm.patcher.ui.screens.profile

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.FakeWatch
import io.openshm.patcher.ui.components.Item
import io.openshm.patcher.ui.components.ScreenList
import io.openshm.patcher.ui.components.QuickPanel
import io.openshm.patcher.ui.theme.LocalReducedMotion

/** The walk-through on a watch, one screen per step, from the watch face to the version numbers. */
enum class GuideStep(@StringRes val caption: Int) {
    Panel(R.string.howto_step_panel),
    Settings(R.string.howto_step_settings),
    About(R.string.howto_step_about),
    Software(R.string.howto_step_software),
}

/**
 * Dialog that shows, on an illustrated watch, how to find the software version: no screenshots,
 * a fake watch whose screen changes with Previous / Next while the shape behind it morphs.
 */
@Composable
fun WatchVersionGuideDialog(onDismiss: () -> Unit) {
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(GuideStep.Panel) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        WatchVersionGuide(
            step = step,
            onStep = { step = it },
            onDone = onDismiss,
            modifier = Modifier.padding(16.dp).widthIn(max = 440.dp),
        )
    }
}

/** The dialog's content, on its own so previews can render any [step] without a window. */
@Composable
fun WatchVersionGuide(step: GuideStep, onStep: (GuideStep) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val steps = GuideStep.entries
    val reducedMotion = LocalReducedMotion.current
    Surface(modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.howto_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            GuideWatch(step, reducedMotion)
            Text(
                stringResource(R.string.howto_progress, step.ordinal + 1, steps.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedContent(
                targetState = step,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                transitionSpec = {
                    fadeIn(tween(220, delayMillis = 90, easing = LinearOutSlowInEasing)) togetherWith fadeOut(tween(90))
                },
                label = "guideCaption",
            ) { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(shown.caption),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (shown == GuideStep.Software) {
                        listOf(R.string.profile_howto_phone, R.string.profile_howto_models, R.string.profile_howto_unsure).forEach {
                            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onStep(steps[step.ordinal - 1]) }, enabled = step.ordinal > 0) {
                    Text(stringResource(R.string.action_previous))
                }
                Spacer(Modifier.weight(1f))
                val last = step.ordinal == steps.lastIndex
                Button(onClick = { if (last) onDone() else onStep(steps[step.ordinal + 1]) }) {
                    Text(stringResource(if (last) R.string.action_done else R.string.action_next))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun GuideStep.polygon() = when (this) {
    GuideStep.Panel -> MaterialShapes.Cookie12Sided
    GuideStep.Settings -> MaterialShapes.Clover8Leaf
    GuideStep.About -> MaterialShapes.Sunny
    GuideStep.Software -> MaterialShapes.Cookie9Sided
}

@Composable
private fun GuideWatch(step: GuideStep, reducedMotion: Boolean) {
    val colors = MaterialTheme.colorScheme
    FakeWatch(
        step = step.ordinal,
        shapes = GuideStep.entries.map { it.polygon() },
        tints = listOf(colors.primaryContainer, colors.tertiaryContainer, colors.secondaryContainer, colors.primaryContainer),
        reducedMotion = reducedMotion,
    ) { shown ->
        when (GuideStep.entries[shown]) {
            GuideStep.Panel -> QuickPanel(reducedMotion)
            GuideStep.Settings -> ScreenList(
                R.string.watch_ui_settings,
                listOf(
                    Item(R.string.watch_ui_notifications, Icons.Rounded.Notifications, Color(0xFFFF8A65)),
                    Item(R.string.watch_ui_display, Icons.Rounded.LightMode, Color(0xFF81C784)),
                    Item(R.string.watch_ui_battery, Icons.Rounded.BatteryFull, Color(0xFF4DD0E1)),
                    Item(R.string.watch_ui_about, Icons.Rounded.Info, Color(0xFFB0B0BA), tap = true),
                ),
                reducedMotion,
            )
            GuideStep.About -> ScreenList(
                R.string.watch_ui_about,
                listOf(
                    Item(R.string.watch_ui_model, value = "SM-R9xx"),
                    Item(R.string.watch_ui_status),
                    Item(R.string.watch_ui_software, tap = true),
                ),
                reducedMotion,
            )
            GuideStep.Software -> ScreenList(
                R.string.watch_ui_software,
                listOf(
                    Item(R.string.watch_ui_oneui, value = "6.0"),
                    Item(R.string.watch_ui_wearos, value = "5.0", mark = true),
                    Item(R.string.watch_ui_android, value = "14", mark = true),
                ),
                reducedMotion,
            )
        }
    }
}
