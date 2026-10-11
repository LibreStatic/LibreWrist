package io.openshm.patcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Decorative wave between the phone and the watch (the "link"). [phase] (radians, read while
 * drawing, so animating it never recomposes) shifts the wave; a growing phase makes it travel from
 * the start towards the end.
 */
@Composable
fun LinkWave(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 4.dp,
    cycles: Int = 3,
    phase: () -> Float = { 0f },
) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val shift = phase()
        val amplitude = size.height / 2f - strokeWidth.toPx() / 2f
        val path = Path()
        val steps = 64
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = t * size.width
            val y = size.height / 2f - amplitude * sin(t * cycles * 2f * PI.toFloat() - shift)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Phone, link and watch, as in the canvas Home / Disable / Welcome artboards. [scale] 1.0 is the
 * Home size (phone 40x66, watch cookie 60).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PhoneWatchIllustration(modifier: Modifier = Modifier, scale: Float = 1f, linkColor: Color = MaterialTheme.colorScheme.primary) {
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 40.dp * scale, height = 66.dp * scale)
                .clip(RoundedCornerShape(12.dp * scale))
                .background(MaterialTheme.colorScheme.primary),
        )
        LinkWave(linkColor, Modifier.size(width = 64.dp * scale, height = 16.dp * scale), strokeWidth = 4.dp * scale)
        Box(
            Modifier
                .size(60.dp * scale)
                .clip(PolygonShape(MaterialShapes.Clover4Leaf))
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

/**
 * Welcome hero: phone, watch and the link between them, drawn as one fixed-size composition that is
 * centered on a rounded panel of any width (canvas Welcome artboard, restyled with expressive shapes).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeHero(modifier: Modifier = Modifier, frozenAt: Float? = null) {
    val colors = MaterialTheme.colorScheme
    val strap = androidx.compose.ui.graphics.lerp(colors.tertiaryContainer, colors.tertiary, 0.35f)
    val clock = rememberHeroClock(frozenAt)
    // Easter egg: the watch tells the phone's real time. Previews keep the classic 10:08.
    val faceTime = rememberWatchTime(fixed = if (frozenAt != null || LocalInspectionMode.current) "10:08" else null)
    Box(
        modifier
            .clearAndSetSemantics { }
            .clip(RoundedCornerShape(48.dp))
            .background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 300.dp, height = 196.dp)) {
            // Soft expressive shapes behind each device.
            Box(
                Modifier.offset((-14).dp, (-6).dp).size(196.dp).rotate(12f)
                    .clip(PolygonShape(MaterialShapes.Cookie12Sided))
                    .background(colors.primaryContainer.copy(alpha = 0.6f)),
            )
            Box(
                Modifier.offset(196.dp, 42.dp).size(124.dp)
                    .clip(PolygonShape(MaterialShapes.Sunny))
                    .background(colors.tertiaryContainer.copy(alpha = 0.7f)),
            )
            // Phone: rounded body, speaker pill, a heart on screen and two content bars.
            Column(
                Modifier
                    .offset(40.dp, 6.dp)
                    .size(width = 104.dp, height = 184.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.primary),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.padding(top = 12.dp).size(width = 30.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(colors.onPrimary.copy(alpha = 0.35f)),
                )
                Box(
                    Modifier.padding(top = 26.dp).size(52.dp)
                        .clip(PolygonShape(MaterialShapes.Cookie9Sided))
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.size(26.dp).graphicsLayer {
                            val beat = heartbeat(clock.value)
                            scaleX = 1f + 0.22f * beat
                            scaleY = 1f + 0.22f * beat
                            alpha = 0.55f + 0.45f * beat
                        },
                    )
                }
                Box(
                    Modifier.padding(top = 18.dp).size(width = 64.dp, height = 9.dp)
                        .clip(RoundedCornerShape(5.dp)).background(colors.inversePrimary),
                )
                Box(
                    Modifier.padding(top = 8.dp).size(width = 42.dp, height = 9.dp)
                        .clip(RoundedCornerShape(5.dp)).background(colors.inversePrimary.copy(alpha = 0.7f)),
                )
            }
            LinkWave(
                colors.tertiary,
                Modifier.offset(150.dp, 88.dp).size(width = 52.dp, height = 20.dp),
                strokeWidth = 3.5.dp,
                cycles = 3,
                phase = { clock.value * LINK_SPEED },
            )
            // Watch: strap behind a round face with a bezel ring and the time.
            Box(
                Modifier.offset(239.dp, 26.dp).size(width = 30.dp, height = 144.dp)
                    .clip(RoundedCornerShape(15.dp)).background(strap),
            )
            Box(
                Modifier.offset(208.dp, 52.dp).size(92.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colors.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(76.dp)
                        .border(2.dp, colors.onTertiary.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape),
                )
                // The face keeps switching between the time and a live-looking ECG trace.
                androidx.compose.material3.Text(
                    faceTime,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = colors.onTertiary,
                    modifier = Modifier.graphicsLayer { alpha = 1f - ecgShown(clock.value) },
                )
                EcgTrace(
                    colors.onTertiary,
                    Modifier.size(width = 54.dp, height = 30.dp).graphicsLayer { alpha = ecgShown(clock.value) },
                    time = { clock.value },
                )
            }
        }
    }
}

/** Radians per second the link wave travels from the phone to the watch. */
private const val LINK_SPEED = 5f

/** Seconds of one heartbeat; the heart and the ECG trace share it. */
private const val BEAT = 1.1f

/** Seconds the watch shows the time, then the ECG, in turn. */
private const val FACE_HOLD = 3.2f
private const val FACE_FADE = 0.45f

/**
 * Seconds since the hero appeared, advanced every frame. Readers must only read it inside draw or
 * graphics-layer lambdas, so the animation never recomposes. With reduced motion it stays at 0
 * (time on the face, the heart at rest); [frozenAt] pins it, for previews of a given moment.
 */
@Composable
private fun rememberHeroClock(frozenAt: Float?): androidx.compose.runtime.State<Float> {
    val clock = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(frozenAt ?: 0f) }
    val reducedMotion = io.openshm.patcher.ui.theme.LocalReducedMotion.current
    androidx.compose.runtime.LaunchedEffect(reducedMotion, frozenAt) {
        if (reducedMotion || frozenAt != null) {
            clock.floatValue = frozenAt ?: 0f
            return@LaunchedEffect
        }
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f }
    }
    return clock
}

/** 0..1 pulse of a heartbeat at [t] seconds: a strong beat, a softer echo, then rest. */
internal fun heartbeat(t: Float): Float {
    val p = (t % BEAT) / BEAT
    fun bump(at: Float, width: Float) = ((1f - kotlin.math.abs(p - at) / width).coerceAtLeast(0f)).let { it * it }
    return maxOf(bump(0.08f, 0.08f), 0.6f * bump(0.26f, 0.07f))
}

/** How much of the ECG (vs the time) the watch face shows at [t] seconds, 0..1. */
internal fun ecgShown(t: Float): Float {
    val cycle = 2f * FACE_HOLD
    val p = t % cycle
    fun ramp(x: Float) = (x / FACE_FADE).coerceIn(0f, 1f)
    // Time first, then the ECG fades in, holds, and fades back out at the end of the cycle.
    return if (p < FACE_HOLD) 0f else minOf(ramp(p - FACE_HOLD), ramp(cycle - p))
}

/** One heartbeat of a stylised ECG (PQRST), x and y in 0..1 of a beat; y up is positive. */
private val EcgBeat = listOf(
    0f to 0f, 0.10f to 0f, 0.14f to 0.12f, 0.18f to 0f, 0.26f to 0f,
    0.29f to -0.15f, 0.33f to 1f, 0.37f to -0.35f, 0.41f to 0f,
    0.55f to 0f, 0.62f to 0.22f, 0.70f to 0f, 1f to 0f,
)

/**
 * A scrolling ECG zigzag, like a monitor sweep: it moves right to left at one beat per [BEAT]
 * seconds and fades out at both ends, so it stays inside the bezel. [time] is read while drawing.
 */
@Composable
private fun EcgTrace(color: Color, modifier: Modifier, time: () -> Float) {
    Canvas(modifier) {
        val beats = 1.6f
        val beatWidth = size.width / beats
        val mid = size.height * 0.62f
        val amp = size.height * 0.55f
        val scroll = (time() / BEAT) % 1f * beatWidth
        val path = Path()
        var first = true
        var beat = -1
        while (beat * beatWidth - scroll < size.width) {
            for ((bx, by) in EcgBeat) {
                val x = (beat + bx) * beatWidth - scroll
                val y = mid - by * amp
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            beat++
        }
        // The path runs past both edges while it scrolls; clip it to the trace's own box.
        clipRect {
            drawPath(
                path,
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to color.copy(alpha = 0f), 0.3f to color, 0.8f to color, 1f to color.copy(alpha = 0f),
                ),
                style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
    }
}

/** Big status blob with a check on top (Build complete / All set). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CheckBlob(size: Dp, container: Color, content: Color, modifier: Modifier = Modifier, description: String) {
    Box(
        modifier
            .size(size)
            .clip(PolygonShape(MaterialShapes.Cookie12Sided))
            .background(container)
            .semantics { contentDescription = description; role = Role.Image },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Rounded.Check,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.4f),
        )
    }
}

/** Small decorative shape used to scatter around hero blobs. */
@Composable
fun DecorShape(polygon: androidx.graphics.shapes.RoundedPolygon, size: Dp, color: Color, modifier: Modifier = Modifier, rotation: Float = 0f) {
    Box(modifier.size(size).rotate(rotation).clip(PolygonShape(polygon)).background(color).clearAndSetSemantics { })
}
