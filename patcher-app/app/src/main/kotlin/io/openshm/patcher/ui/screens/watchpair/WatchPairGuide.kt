package io.openshm.patcher.ui.screens.watchpair

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import io.openshm.patcher.ui.components.QuickPanel
import io.openshm.patcher.ui.components.TouchPoint
import io.openshm.patcher.ui.components.watchSlide
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.FakeWatch
import io.openshm.patcher.ui.components.Item
import io.openshm.patcher.ui.components.ScreenList
import io.openshm.patcher.ui.components.WatchScreen
import io.openshm.patcher.ui.components.WatchText
import io.openshm.patcher.ui.theme.LocalReducedMotion

/** The captions of the walk-through that turns on pairing on a One UI Watch, in order. */
private val guideCaptions = listOf(
    R.string.watchpair_guide_1,
    R.string.watchpair_guide_3,
    R.string.watchpair_guide_wireless,
    R.string.watchpair_guide_5,
)

/**
 * The short captions of the looping animation of each guide step, in order. A step with no
 * sub-frames (the pairing code) shows a single screen and no numbers beside the watch.
 */
private val subCaptions = listOf(
    listOf(
        R.string.watchpair_dev_1, R.string.watchpair_dev_2, R.string.watchpair_dev_3,
        R.string.watchpair_dev_4, R.string.watchpair_dev_5, R.string.watchpair_dev_6,
    ),
    listOf(
        R.string.watchpair_dev_1, R.string.watchpair_wifi_2, R.string.watchpair_wifi_3,
        R.string.watchpair_wifi_4, R.string.watchpair_wifi_5,
    ),
    listOf(
        R.string.watchpair_wl_1, R.string.watchpair_wl_2, R.string.watchpair_wl_3,
        R.string.watchpair_wl_4, R.string.watchpair_wl_5,
    ),
    emptyList(),
)

private const val WIRELESS_STEP = 2
private const val SUB_STEP_MS = 2500L
private const val DEV_TAPS = 7

/** Previews and the renderer set this to hold the developer-mode animation on one sub-step. */
internal val LocalFrozenGuideSubStep = compositionLocalOf<Int?> { null }

internal val WATCH_GUIDE_STEPS = guideCaptions.size

/**
 * Inline guide on an illustrated watch: how to enable developer mode and Wireless debugging and
 * reach the pairing code. Same look and motion as the watch-version guide, shown at the top of the
 * pairing screen until the watch is found.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WatchPairGuide(
    step: Int,
    onStep: (Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    frozenSubStep: Int? = LocalFrozenGuideSubStep.current,
) {
    val reducedMotion = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val last = WATCH_GUIDE_STEPS - 1
    // Each step with sub-frames loops through them forever; tapping a number jumps and the loop goes on from there.
    val captions = subCaptions[step]
    var userSub by rememberSaveable(step) { mutableIntStateOf(0) }
    val sub = (frozenSubStep ?: userSub).coerceIn(0, (captions.size - 1).coerceAtLeast(0))
    val animating = frozenSubStep == null && !reducedMotion && captions.isNotEmpty()
    LaunchedEffect(step, userSub, animating) {
        if (!animating) return@LaunchedEffect
        delay(SUB_STEP_MS)
        userSub = (userSub + 1) % captions.size
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHigh) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.watchpair_guide_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FakeWatch(
                    step = step,
                    shapes = listOf(MaterialShapes.Cookie12Sided, MaterialShapes.Clover8Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie9Sided),
                    tints = listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer, colors.primaryContainer),
                    reducedMotion = reducedMotion,
                    size = if (captions.isEmpty()) 248.dp else 208.dp,
                ) { shown -> GuideScreen(shown, sub, captions.size, reducedMotion, animate = frozenSubStep == null) }
                if (captions.isNotEmpty()) {
                    SubStepChips(sub, captions.size, onSelect = { userSub = it })
                }
            }
            if (captions.isNotEmpty()) {
                Text(
                    stringResource(R.string.watchpair_dev_line, sub + 1, stringResource(captions[sub])),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                stringResource(R.string.howto_progress, step + 1, WATCH_GUIDE_STEPS),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            AnimatedContent(
                targetState = step,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                transitionSpec = {
                    fadeIn(tween(220, delayMillis = 90, easing = LinearOutSlowInEasing)) togetherWith fadeOut(tween(90))
                },
                label = "pairGuideCaption",
            ) { shown ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(guideCaptions[shown]),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (shown == WIRELESS_STEP) {
                        Text(
                            stringResource(R.string.watchpair_guide_wireless_older),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onStep(step - 1) }, enabled = step > 0) { Text(stringResource(R.string.action_previous)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (step == last) onDone() else onStep(step + 1) }) {
                    Text(stringResource(if (step == last) R.string.action_done else R.string.action_next))
                }
            }
        }
    }
}

/** The numbers beside the watch: which sub-step of the first step is shown; tap one to jump to it. */
@Composable
private fun SubStepChips(selected: Int, count: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.selectableGroup(), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(count) { index ->
            val description = stringResource(R.string.watchpair_dev_chip, index + 1, count)
            val on = index == selected
            Box(
                Modifier.minimumInteractiveComponentSize()
                    .semantics { contentDescription = description }
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(if (on) colors.primary else colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) colors.onPrimary else colors.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }
    }
}

/** What the fake watch shows at each step; steps with sub-frames loop through them ([sub] of [subCount]). */
@Composable
private fun GuideScreen(step: Int, sub: Int, subCount: Int, reducedMotion: Boolean, animate: Boolean) {
    if (subCount == 0) {
        PairingCodeScreen()
        return
    }
    AnimatedContent(
        targetState = sub,
        transitionSpec = {
            // The loop wraps from the last sub-frame to the first: that still moves forward.
            val forward = targetState > initialState || (targetState == 0 && initialState == subCount - 1)
            watchSlide(reducedMotion, forward)
        },
        label = "guideSubFrame",
    ) { shown ->
        Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) {
            when (step) {
                0 -> DeveloperModeFrame(shown, reducedMotion, animate)
                1 -> WifiFrame(shown, reducedMotion)
                else -> WirelessFrame(shown, reducedMotion, animate)
            }
        }
    }
}

@Composable
private fun DeveloperModeFrame(sub: Int, reducedMotion: Boolean, animate: Boolean) {
    when (sub) {
        0 -> QuickPanel(reducedMotion)
        1 -> SettingsScroll(withDeveloper = false, scrolls = animate, tap = SettingsTap.About, reducedMotion)
        2 -> ScreenList(
            R.string.watch_ui_about,
            listOf(
                Item(R.string.watch_ui_model, value = "SM-R9xx"),
                Item(R.string.watch_ui_status),
                Item(R.string.watch_ui_software, tap = true),
            ),
            reducedMotion,
        )
        3 -> SoftwareTaps(reducedMotion, animate)
        4 -> Message(R.string.watch_ui_devmode_on)
        else -> SettingsScroll(withDeveloper = true, scrolls = false, tap = SettingsTap.None, reducedMotion)
    }
}

@Composable
private fun WifiFrame(sub: Int, reducedMotion: Boolean) {
    when (sub) {
        0 -> QuickPanel(reducedMotion)
        1 -> ScreenList(
            R.string.watch_ui_settings,
            listOf(
                Item(R.string.watch_ui_notifications, Icons.Rounded.Notifications, Color(0xFFFF8A65)),
                Item(R.string.watch_ui_display, Icons.Rounded.LightMode, Color(0xFF81C784)),
                Item(R.string.watch_ui_battery, Icons.Rounded.BatteryFull, Color(0xFF4DD0E1)),
                Item(R.string.watch_ui_connections, Icons.Rounded.Wifi, Color(0xFF8FB0FF), tap = true),
            ),
            reducedMotion,
        )
        2 -> ScreenList(
            R.string.watch_ui_connections,
            listOf(
                Item(R.string.watch_ui_wifi, Icons.Rounded.Wifi, Color(0xFF4DD0E1), tap = true),
                Item(R.string.watch_ui_bluetooth, Icons.Rounded.Bluetooth, Color(0xFF8FB0FF)),
            ),
            reducedMotion,
        )
        else -> {
            val connected = sub == 4
            ScreenList(
                R.string.watch_ui_wifi,
                listOf(
                    Item(
                        R.string.watch_ui_network_home,
                        value = stringResource(if (connected) R.string.watch_ui_connected else R.string.watch_ui_same_network),
                        tap = !connected,
                        mark = connected,
                    ),
                    Item(R.string.watch_ui_network_guest),
                ),
                reducedMotion,
            )
        }
    }
}

@Composable
private fun WirelessFrame(sub: Int, reducedMotion: Boolean, animate: Boolean) {
    when (sub) {
        0 -> SettingsScroll(withDeveloper = true, scrolls = animate, tap = SettingsTap.None, reducedMotion)
        1 -> SettingsScroll(withDeveloper = true, scrolls = false, tap = SettingsTap.Developer, reducedMotion)
        2, 4 -> ScreenList(
            R.string.watch_ui_developer,
            listOf(
                Item(R.string.watch_ui_adb, toggle = false),
                Item(R.string.watch_ui_wireless, toggle = sub == 4, tap = sub == 2, mark = sub == 4),
            ),
            reducedMotion,
        )
        else -> AllowPrompt(reducedMotion)
    }
}

/** The watch asking whether to allow wireless debugging on this network, with Allow to tap. */
@Composable
private fun AllowPrompt(reducedMotion: Boolean) {
    Column(Modifier.width(128.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchText(stringResource(R.string.watch_ui_allow_prompt), WatchScreen.text, 10.sp, FontWeight.SemiBold)
        Box(contentAlignment = Alignment.CenterEnd) {
            Row(
                Modifier.clip(CircleShape).background(WatchScreen.accent.copy(alpha = 0.22f)).padding(horizontal = 18.dp, vertical = 5.dp),
            ) { WatchText(stringResource(R.string.watch_ui_allow), WatchScreen.accent, 10.sp, FontWeight.SemiBold) }
            TouchPoint(reducedMotion, Modifier.padding(end = 6.dp))
        }
    }
}

/** Which Settings row is tapped once the list has scrolled to its end. */
private enum class SettingsTap { About, Developer, None }

/**
 * The Settings list. With [scrolls] it scrolls down to its bottom first, otherwise it starts there;
 * then [tap] is tapped. With [withDeveloper] the Developer options row shows under About watch.
 */
@Composable
private fun SettingsScroll(withDeveloper: Boolean, scrolls: Boolean, tap: SettingsTap, reducedMotion: Boolean) {
    val still = !scrolls || reducedMotion
    val progress = remember { Animatable(if (still) 1f else 0f) }
    var arrived by remember { mutableStateOf(still) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        delay(300)
        progress.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        arrived = true
    }
    val items = buildList {
        add(Item(R.string.watch_ui_notifications, Icons.Rounded.Notifications, Color(0xFFFF8A65)))
        add(Item(R.string.watch_ui_display, Icons.Rounded.LightMode, Color(0xFF81C784)))
        add(Item(R.string.watch_ui_battery, Icons.Rounded.BatteryFull, Color(0xFF4DD0E1)))
        add(Item(R.string.watch_ui_connections, Icons.Rounded.Wifi, Color(0xFF8FB0FF)))
        add(Item(R.string.watch_ui_apps, Icons.Rounded.Apps, Color(0xFFBA68C8)))
        add(Item(R.string.watch_ui_security, Icons.Rounded.Lock, Color(0xFFFFD54F)))
        add(Item(R.string.watch_ui_about, Icons.Rounded.Info, Color(0xFFB0B0BA), tap = arrived && tap == SettingsTap.About))
        if (withDeveloper) add(Item(R.string.watch_ui_developer, Icons.Rounded.Build, Color(0xFF8FB0FF), tap = arrived && tap == SettingsTap.Developer, mark = tap != SettingsTap.Developer))
    }
    Box(Modifier.height(132.dp).clipToBounds().scrolledBy { progress.value }) {
        ScreenList(R.string.watch_ui_settings, items, reducedMotion)
    }
}

/** Lays [this] out at its full height inside a fixed viewport and scrolls it by [progress] (0 top, 1 bottom). */
private fun Modifier.scrolledBy(progress: () -> Float) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val viewport = constraints.maxHeight
    layout(placeable.width, viewport) {
        placeable.placeRelative(0, -((placeable.height - viewport).coerceAtLeast(0) * progress()).roundToInt())
    }
}

/** Software information with Software version tapped again and again, and a counter of the taps. */
@Composable
private fun SoftwareTaps(reducedMotion: Boolean, animate: Boolean) {
    var count by remember { mutableIntStateOf(if (animate && !reducedMotion) 1 else DEV_TAPS) }
    LaunchedEffect(Unit) {
        if (!animate || reducedMotion) return@LaunchedEffect
        for (n in 2..DEV_TAPS) {
            delay(300)
            count = n
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ScreenList(
            R.string.watch_ui_software,
            listOf(
                Item(R.string.watch_ui_oneui, value = "6.0"),
                Item(R.string.watch_ui_software_version, tap = true),
            ),
            reducedMotion,
        )
        Row(
            Modifier.clip(CircleShape).background(WatchScreen.accent.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 3.dp),
        ) { WatchText("$count / $DEV_TAPS", WatchScreen.accent, 10.sp, FontWeight.SemiBold) }
    }
}

/** A short confirmation chip, like the toast the watch shows. */
@Composable
private fun Message(@StringRes text: Int) {
    Row(
        Modifier.clip(CircleShape).background(WatchScreen.accent.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 3.dp),
    ) { WatchText(stringResource(text), WatchScreen.accent, 8.sp, FontWeight.SemiBold) }
}

/** The Pair new device screen: a 6-digit code with the IP address and port under it. */
@Composable
private fun PairingCodeScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        WatchText(stringResource(R.string.watch_ui_pair_new), WatchScreen.accent, 10.sp, FontWeight.SemiBold)
        Text("123 456", color = WatchScreen.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        Text("192.168.1.20:37115", color = WatchScreen.dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}
