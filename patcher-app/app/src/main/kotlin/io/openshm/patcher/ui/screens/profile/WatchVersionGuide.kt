package io.openshm.patcher.ui.screens.profile

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.graphics.shapes.Morph
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.MorphShape
import io.openshm.patcher.ui.components.rememberWatchTime
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

/** A fixed dark palette: a watch screen looks the same whatever the phone's theme. */
private object WatchScreen {
    val background = Color(0xFF0E0F12)
    val text = Color(0xFFF2F2F5)
    val dim = Color(0xFF9C9CA6)
    val accent = Color(0xFF8FB0FF)
    val tile = Color(0xFF26272C)
}

/** The watch: a morphing shape behind it, strap, case, bezel and a screen that slides per step. */
@Composable
private fun GuideWatch(step: GuideStep, reducedMotion: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tints = listOf(colors.primaryContainer, colors.tertiaryContainer, colors.secondaryContainer, colors.primaryContainer)
    // The shape morphs from the step it was showing to the new one; quick taps start from there.
    var from by remember { mutableStateOf(step) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(step) {
        if (from == step) return@LaunchedEffect
        if (reducedMotion) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
        }
        from = step
    }
    val morph = remember(from, step) { Morph(from.polygon(), step.polygon()) }
    val turn by animateFloatAsState(
        if (reducedMotion) 0f else step.ordinal * 40f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "guideTurn",
    )
    val strap = lerp(colors.tertiaryContainer, colors.tertiary, 0.35f)

    Box(Modifier.size(248.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { rotationZ = turn }
                .clip(MorphShape(morph) { progress.value })
                .background(lerp(tints[from.ordinal], tints[step.ordinal], progress.value.coerceIn(0f, 1f))),
        )
        Box(Modifier.size(width = 76.dp, height = 232.dp).clip(RoundedCornerShape(30.dp)).background(strap))
        Box(
            Modifier.size(188.dp).clip(CircleShape).background(Color(0xFF2B2C31))
                .border(3.dp, Color(0xFF55565E), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(164.dp).clip(CircleShape).background(WatchScreen.background), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (reducedMotion) {
                            fadeIn(tween(150)) togetherWith fadeOut(tween(150))
                        } else {
                            // Like scrolling on the watch: forward moves the screen up, back moves it down.
                            val sign = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            val slide = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
                            (slideInVertically(slide) { sign * it / 2 } + fadeIn(tween(200, delayMillis = 60))) togetherWith
                                (slideOutVertically(slide) { -sign * it / 2 } + fadeOut(tween(90))) using
                                SizeTransform(clip = true)
                        }
                    },
                    label = "guideScreen",
                ) { shown ->
                    Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) {
                        when (shown) {
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
            }
        }
    }
}

/**
 * One row of a fake watch list. [tap] marks the row to tap next (highlighted, with a pulsing touch
 * point); [mark] highlights a row to read.
 */
private class Item(
    @StringRes val label: Int,
    val icon: ImageVector? = null,
    val iconTint: Color = WatchScreen.dim,
    val value: String? = null,
    val tap: Boolean = false,
    val mark: Boolean = false,
)

@Composable
private fun ScreenList(@StringRes title: Int, items: List<Item>, reducedMotion: Boolean) {
    Column(
        Modifier.width(132.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WatchText(stringResource(title), WatchScreen.accent, 10.sp, FontWeight.SemiBold)
        Spacer(Modifier.size(2.dp))
        items.forEach { item ->
            val highlighted = item.tap || item.mark
            Box(contentAlignment = Alignment.CenterEnd) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (highlighted) WatchScreen.accent.copy(alpha = 0.22f) else WatchScreen.tile)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.icon?.let {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(item.iconTint), contentAlignment = Alignment.Center) {
                            Icon(it, contentDescription = null, tint = WatchScreen.background, modifier = Modifier.size(10.dp))
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        WatchText(stringResource(item.label), WatchScreen.text, 9.sp)
                        item.value?.let { WatchText(it, if (item.mark) WatchScreen.accent else WatchScreen.dim, 9.sp, FontWeight.SemiBold) }
                    }
                }
                if (item.tap) TouchPoint(reducedMotion, Modifier.padding(end = 6.dp))
            }
        }
    }
}

/** The quick panel opened by swiping down: the time on top, round tiles, the gear to tap. */
@Composable
private fun QuickPanel(reducedMotion: Boolean) {
    val tiles = listOf(Icons.Rounded.Wifi, Icons.Rounded.Bluetooth, Icons.Rounded.DoNotDisturbOn, Icons.Rounded.FlashlightOn)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WatchText(rememberWatchTime(), WatchScreen.text, 13.sp, FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.take(2).forEach { Tile(it) }
            Box(contentAlignment = Alignment.Center) {
                Tile(Icons.Rounded.Settings, highlighted = true)
                TouchPoint(reducedMotion)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { tiles.drop(2).forEach { Tile(it) } }
    }
}

@Composable
private fun Tile(icon: ImageVector, highlighted: Boolean = false) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (highlighted) WatchScreen.accent else WatchScreen.tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlighted) WatchScreen.background else WatchScreen.text, modifier = Modifier.size(18.dp))
    }
}

/** A finger's touch: a ring that keeps swelling and fading over what to tap. Static with motion off. */
@Composable
private fun TouchPoint(reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val pulse = if (reducedMotion) {
        0.4f
    } else {
        rememberInfiniteTransition(label = "touch").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1200, easing = LinearOutSlowInEasing), RepeatMode.Restart), label = "touchPulse",
        ).value
    }
    Box(
        modifier.size(22.dp).graphicsLayer {
            scaleX = 0.6f + 0.7f * pulse
            scaleY = 0.6f + 0.7f * pulse
            alpha = 1f - pulse
        }.clip(CircleShape).background(Color.White.copy(alpha = 0.55f)),
    )
}

@Composable
private fun WatchText(text: String, color: Color, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.2f, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
