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
    R.string.watchpair_guide_2,
    R.string.watchpair_guide_3,
    R.string.watchpair_guide_4,
    R.string.watchpair_guide_5,
)

internal val WATCH_GUIDE_STEPS = guideCaptions.size

/**
 * Inline guide on an illustrated watch: how to enable developer mode and Wireless debugging and
 * reach the pairing code. Same look and motion as the watch-version guide, shown at the top of the
 * pairing screen until the watch is found.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WatchPairGuide(step: Int, onStep: (Int) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val last = WATCH_GUIDE_STEPS - 1
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
            FakeWatch(
                step = step,
                shapes = listOf(
                    MaterialShapes.Cookie12Sided, MaterialShapes.Clover8Leaf, MaterialShapes.Sunny,
                    MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf,
                ),
                tints = listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer, colors.primaryContainer, colors.secondaryContainer),
                reducedMotion = reducedMotion,
            ) { shown -> GuideScreen(shown, reducedMotion) }
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
                Text(
                    stringResource(guideCaptions[shown]),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
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

/** What the fake watch shows at each step. */
@Composable
private fun GuideScreen(step: Int, reducedMotion: Boolean) {
    when (step) {
        0 -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ScreenList(
                R.string.watch_ui_software,
                listOf(
                    Item(R.string.watch_ui_oneui, value = "6.0"),
                    Item(R.string.watch_ui_software_version, tap = true),
                ),
                reducedMotion,
            )
            Message(R.string.watch_ui_devmode_on)
        }
        1 -> ScreenList(
            R.string.watch_ui_developer,
            listOf(
                Item(R.string.watch_ui_adb, toggle = false, tap = true),
                Item(R.string.watch_ui_wireless, toggle = false),
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
        3 -> ScreenList(
            R.string.watch_ui_developer,
            listOf(
                Item(R.string.watch_ui_adb, toggle = true),
                Item(R.string.watch_ui_wireless, toggle = false, tap = true),
            ),
            reducedMotion,
        )
        else -> PairingCodeScreen()
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
