package io.openshm.patcher.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/*
 * The pieces of the illustrated watch used by the step-by-step guides: a morphing shape behind a
 * round watch whose screen slides per step, list screens, a pulsing touch point. No screenshots.
 */

/** A fixed dark palette: a watch screen looks the same whatever the phone's theme. */
internal object WatchScreen {
    val background = Color(0xFF0E0F12)
    val text = Color(0xFFF2F2F5)
    val dim = Color(0xFF9C9CA6)
    val accent = Color(0xFF8FB0FF)
    val tile = Color(0xFF26272C)
}

/**
 * The watch: a morphing shape behind it, strap, case, bezel and a screen that slides per step.
 * [shapes] and [tints] are indexed by [step]; [screen] draws what the watch shows at a step.
 */
@Composable
internal fun FakeWatch(
    step: Int,
    shapes: List<RoundedPolygon>,
    tints: List<Color>,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 248.dp,
    screen: @Composable (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The shape morphs from the step it was showing to the new one; quick taps start from there.
    var from by remember { mutableIntStateOf(step) }
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
    val morph = remember(from, step) { Morph(shapes[from], shapes[step]) }
    val turn by animateFloatAsState(
        if (reducedMotion) 0f else step * 40f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "guideTurn",
    )
    val strap = lerp(colors.tertiaryContainer, colors.tertiary, 0.35f)

    val scale = size / 248.dp
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(Modifier.requiredSize(248.dp).graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { rotationZ = turn }
                    .clip(MorphShape(morph) { progress.value })
                    .background(lerp(tints[from], tints[step], progress.value.coerceIn(0f, 1f))),
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
                        transitionSpec = { watchSlide(reducedMotion, forward = targetState > initialState) },
                        label = "guideScreen",
                    ) { shown ->
                        Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) { screen(shown) }
                    }
                }
            }
        }
    }
}

/**
 * The slide of a watch screen: forward moves the screen up, back moves it down, like scrolling on
 * the watch; a crossfade with reduced motion.
 */
internal fun AnimatedContentTransitionScope<*>.watchSlide(reducedMotion: Boolean, forward: Boolean): ContentTransform {
    if (reducedMotion) return fadeIn(tween(150)) togetherWith fadeOut(tween(150))
    val sign = if (forward) 1 else -1
    val slide = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)
    return (slideInVertically(slide) { sign * it / 2 } + fadeIn(tween(200, delayMillis = 60))) togetherWith
        (slideOutVertically(slide) { -sign * it / 2 } + fadeOut(tween(90))) using
        SizeTransform(clip = true)
}

/**
 * One row of a fake watch list. [tap] marks the row to tap next (highlighted, with a pulsing touch
 * point); [mark] highlights a row to read; [toggle] draws a switch (on when true).
 */
internal class Item(
    @StringRes val label: Int,
    val icon: ImageVector? = null,
    val iconTint: Color = WatchScreen.dim,
    val value: String? = null,
    val tap: Boolean = false,
    val mark: Boolean = false,
    val toggle: Boolean? = null,
)

@Composable
internal fun ScreenList(@StringRes title: Int, items: List<Item>, reducedMotion: Boolean) {
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
                    item.toggle?.let { WatchSwitch(it) }
                }
                if (item.tap) TouchPoint(reducedMotion, Modifier.padding(end = 6.dp))
            }
        }
    }
}

@Composable
private fun WatchSwitch(on: Boolean) {
    Box(
        Modifier.size(width = 20.dp, height = 11.dp).clip(CircleShape)
            .background(if (on) WatchScreen.accent else WatchScreen.dim.copy(alpha = 0.5f)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(1.5.dp).size(8.dp).clip(CircleShape).background(if (on) WatchScreen.background else WatchScreen.text))
    }
}

@Composable
internal fun Tile(icon: ImageVector, highlighted: Boolean = false) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(if (highlighted) WatchScreen.accent else WatchScreen.tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (highlighted) WatchScreen.background else WatchScreen.text, modifier = Modifier.size(18.dp))
    }
}

/** The quick panel opened by swiping down: the time on top, round tiles, the gear to tap. */
@Composable
internal fun QuickPanel(reducedMotion: Boolean) {
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

/** A finger's touch: a ring that keeps swelling and fading over what to tap. Static with motion off. */
@Composable
internal fun TouchPoint(reducedMotion: Boolean, modifier: Modifier = Modifier) {
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
internal fun WatchText(text: String, color: Color, size: TextUnit, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = color, fontSize = size, fontWeight = weight, lineHeight = size * 1.2f, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
