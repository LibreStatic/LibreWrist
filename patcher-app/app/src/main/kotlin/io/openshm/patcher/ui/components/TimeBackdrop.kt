package io.openshm.patcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.openshm.patcher.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** What one floating motif depicts; every kind is about watches or time. */
private enum class Motif { Clock, Watch, Hourglass, Heart, Alarm, Stopwatch, Pulse, Calendar, Moon, Timer }

/** One floating motif. Positions are fractions of the backdrop; [depth] drives size, alpha and drift. */
private class Floater(
    val motif: Motif,
    val face: Shape,
    val role: Int,
    val x: Float,
    val y: Float,
    val sizeDp: Float,
    val depth: Float,
    val spin: Float,
    val phase: Float,
    val wobble: Float,
) {
    // Building a polygon outline is costly and a motif's sizes never change, so each is built once.
    private val cache = HashMap<Pair<Shape, Float>, Outline>()

    fun outline(shape: Shape, side: Float, direction: LayoutDirection, density: Density): Outline =
        cache.getOrPut(shape to side) { shape.createOutline(Size(side, side), direction, density) }
}

/**
 * Decorative field of Material expressive shapes behind the guided setup: clock faces whose hands
 * turn, watches, stopwatches, timer rings, hourglasses, calendars, moons, hearts, heartbeat traces
 * and ringing alarms, drifting and turning slowly. Mostly fills
 * the side margins of large screens; on a phone it sits faintly behind the content.
 *
 * When [dispersed] becomes true the motifs fly out from the centre and fade; clearing it brings them
 * back in. Colours are container roles at low alpha, so they never carry content. With system
 * animations off the field is static and the exit is immediate.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TimeBackdrop(dispersed: Boolean, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val fills = listOf(colors.primaryContainer, colors.secondaryContainer, colors.tertiaryContainer)
    val marks = listOf(colors.primary, colors.secondary, colors.tertiary)
    val seed = rememberSaveable { Random.nextInt() }

    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion, dispersed) {
        // Nothing to animate while the field is gone or animations are off.
        if (reducedMotion || dispersed) return@LaunchedEffect
        val start = withFrameNanos { it } - (time * 1_000_000_000f).toLong()
        while (true) withFrameNanos { time = (it - start) / 1_000_000_000f }
    }
    val exit = remember { Animatable(if (dispersed) 1f else 0f, visibilityThreshold = 0.0005f) }
    LaunchedEffect(dispersed, reducedMotion) {
        val target = if (dispersed) 1f else 0f
        if (reducedMotion) exit.snapTo(target)
        else exit.animateTo(target, tween(durationMillis = if (dispersed) 800 else 1100, easing = FastOutSlowInEasing))
    }
    if (exit.value >= 1f) return

    BoxWithConstraints(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val count = when {
            maxWidth >= 840.dp -> 15
            maxWidth >= 600.dp -> 11
            else -> 7
        }
        val faces = listOf(
            MaterialShapes.Cookie12Sided, MaterialShapes.Circle, MaterialShapes.Cookie9Sided,
            MaterialShapes.Clover8Leaf, MaterialShapes.Cookie6Sided, MaterialShapes.Cookie4Sided,
            MaterialShapes.Cookie7Sided, MaterialShapes.SoftBurst, MaterialShapes.PuffyDiamond,
            MaterialShapes.Square, MaterialShapes.Pentagon, MaterialShapes.Flower,
        ).map { it.toShape() }
        val triangle = MaterialShapes.Triangle.toShape()
        val heart = MaterialShapes.Heart.toShape()
        val bell = MaterialShapes.Sunny.toShape()
        // Keyed on density too: each motif caches outlines built at its pixel size.
        val floaters = remember(seed, count, LocalDensity.current) {
            val random = Random(seed)
            // Dealt from a shuffled deck: no motif repeats before every other one has appeared.
            val deck = List((count + Motif.entries.size - 1) / Motif.entries.size) { Motif.entries.shuffled(random) }.flatten()
            List(count) { index ->
                val depth = 0.4f + random.nextFloat() * 0.6f
                Floater(
                    motif = deck[index],
                    face = faces[random.nextInt(faces.size)],
                    role = random.nextInt(3),
                    // Spread over a jittered grid so the motifs do not pile up.
                    x = (index + 0.2f + random.nextFloat() * 0.6f) / count,
                    y = random.nextFloat(),
                    sizeDp = 36f + 104f * depth,
                    depth = depth,
                    spin = (4f + random.nextFloat() * 10f) * if (random.nextBoolean()) 1f else -1f,
                    phase = random.nextFloat() * 2f * PI.toFloat(),
                    wobble = 10f + random.nextFloat() * 18f,
                )
            }.shuffled(random)
        }

        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val diagonal = hypot(size.width, size.height)
            val e = FastOutSlowInEasing.transform(exit.value)
            floaters.forEach { f ->
                val side = f.sizeDp.dp.toPx()
                val wobble = f.wobble.dp.toPx()
                var cx = f.x * size.width + sin(time * 0.3f + f.phase) * wobble
                var cy = f.y * size.height + cos(time * 0.23f + f.phase * 1.3f) * wobble
                val dx = cx - center.x
                val dy = cy - center.y
                val distance = hypot(dx, dy).coerceAtLeast(1f)
                // The exit pushes every motif outwards along the line from the centre.
                val push = e * diagonal * (0.6f + f.depth)
                cx += dx / distance * push
                cy += dy / distance * push
                val alpha = (0.06f + 0.08f * f.depth) * (1f - e)
                val angle = f.phase * 57.3f + time * f.spin + e * f.spin * 12f
                translate(cx - side / 2f, cy - side / 2f) {
                    val pivot = Offset(side / 2f, side / 2f)
                    scale(1f + 0.4f * e, pivot) {
                        rotate(angle, pivot) {
                            drawMotif(f, side, fills[f.role], marks[f.role], alpha, triangle, heart, bell, time)
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawMotif(
    f: Floater,
    side: Float,
    fill: Color,
    mark: Color,
    alpha: Float,
    triangle: Shape,
    heart: Shape,
    bell: Shape,
    time: Float,
) {
    fun shape(shape: Shape, s: Float, at: Offset, color: Color = fill) =
        translate(at.x, at.y) { drawOutline(f.outline(shape, s, layoutDirection, this), color, alpha = alpha) }

    when (f.motif) {
        Motif.Clock -> {
            shape(f.face, side, Offset.Zero)
            drawHands(Offset(side / 2f, side / 2f), side * 0.36f, mark, alpha, time, f.phase)
        }
        Motif.Watch -> {
            // A strap running behind a smaller face.
            val face = side * 0.62f
            val strapWidth = side * 0.34f
            drawRoundRect(
                fill,
                Offset((side - strapWidth) / 2f, 0f),
                Size(strapWidth, side),
                CornerRadius(strapWidth / 2f),
                alpha = alpha * 0.8f,
            )
            val corner = (side - face) / 2f
            shape(f.face, face, Offset(corner, corner))
            drawHands(Offset(side / 2f, side / 2f), face * 0.34f, mark, alpha, time, f.phase)
        }
        Motif.Hourglass -> {
            // Two triangles tip to tip; the whole glass turns over slowly with the motif's spin.
            val half = side * 0.5f
            val inset = (side - half) / 2f
            rotate(180f, Offset(side / 2f, half / 2f)) { shape(triangle, half, Offset(inset, 0f)) }
            shape(triangle, half, Offset(inset, half))
        }
        Motif.Heart -> {
            // A slow heartbeat: two quick swells, then a rest.
            val beat = (time + f.phase) % 1.6f
            val pulse = 1f + 0.08f * (bump(beat, 0f) + 0.7f * bump(beat, 0.28f))
            scale(pulse, Offset(side / 2f, side / 2f)) { shape(heart, side * 0.8f, Offset(side * 0.1f, side * 0.1f)) }
        }
        Motif.Alarm -> {
            // A ringing alarm: a scalloped bell that shivers now and then, with a clock inside.
            val ring = (time * 0.5f + f.phase) % 3f
            val shiver = if (ring < 0.6f) sin(ring * 60f) * 6f * (1f - ring / 0.6f) else 0f
            rotate(shiver, Offset(side / 2f, side / 2f)) {
                shape(bell, side, Offset.Zero)
                drawHands(Offset(side / 2f, side / 2f), side * 0.28f, mark, alpha, time, f.phase)
            }
        }
        Motif.Stopwatch -> {
            // A round face with a crown on top and one hand sweeping a turn every eight seconds.
            val face = side * 0.78f
            val center = Offset(side / 2f, side * 0.56f)
            drawRoundRect(fill, Offset(side * 0.42f, side * 0.04f), Size(side * 0.16f, side * 0.12f), CornerRadius(side * 0.04f), alpha = alpha)
            drawCircle(fill, face / 2f, center, alpha = alpha)
            val angle = ((time / 8f + f.phase) % 1f) * 2f * PI.toFloat() - PI.toFloat() / 2f
            val reach = face * 0.36f
            drawLine(
                mark, center, Offset(center.x + cos(angle) * reach, center.y + sin(angle) * reach),
                strokeWidth = (side * 0.05f).coerceAtLeast(2f), cap = StrokeCap.Round, alpha = alpha * 1.5f,
            )
            drawCircle(mark, (side * 0.04f).coerceAtLeast(2f), center, alpha = alpha * 1.5f)
        }
        Motif.Pulse -> {
            // A heartbeat trace on its own, the line a watch draws while measuring.
            val path = Path()
            PulsePoints.forEachIndexed { i, (x, y) ->
                val px = x * side
                val py = side / 2f - y * side * 0.36f
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(
                path, mark, alpha = alpha * 1.5f,
                style = Stroke((side * 0.06f).coerceAtLeast(2f), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        Motif.Calendar -> {
            // A page with two rings on top and a few dates.
            val top = side * 0.16f
            drawRoundRect(fill, Offset(side * 0.1f, top), Size(side * 0.8f, side * 0.72f), CornerRadius(side * 0.14f), alpha = alpha)
            val ring = (side * 0.05f).coerceAtLeast(2f)
            listOf(0.32f, 0.68f).forEach { x ->
                drawLine(mark, Offset(side * x, side * 0.08f), Offset(side * x, side * 0.26f), ring, StrokeCap.Round, alpha = alpha * 1.5f)
            }
            for (row in 0..1) for (col in 0..2) {
                drawCircle(mark, side * 0.045f, Offset(side * (0.3f + col * 0.2f), side * (0.52f + row * 0.18f)), alpha = alpha * 1.3f)
            }
        }
        Motif.Moon -> {
            // A crescent: night time, the other half of the clock.
            val moon = Path.combine(
                PathOperation.Difference,
                Path().apply { addOval(Rect(Offset(side / 2f, side / 2f), side * 0.42f)) },
                Path().apply { addOval(Rect(Offset(side * 0.68f, side * 0.38f), side * 0.34f)) },
            )
            drawPath(moon, fill, alpha = alpha)
            drawCircle(mark, side * 0.04f, Offset(side * 0.8f, side * 0.72f), alpha = alpha * 1.3f)
        }
        Motif.Timer -> {
            // A countdown ring: the arc empties and refills slowly.
            val stroke = side * 0.12f
            val inset = stroke / 2f + side * 0.08f
            val arcSize = Size(side - 2f * inset, side - 2f * inset)
            drawArc(fill, 0f, 360f, false, Offset(inset, inset), arcSize, alpha = alpha, style = Stroke(stroke))
            val left = 1f - (time / 20f + f.phase) % 1f
            drawArc(
                mark, -90f, 360f * left, false, Offset(inset, inset), arcSize, alpha = alpha * 1.3f,
                style = Stroke(stroke * 0.6f, cap = StrokeCap.Round),
            )
        }
    }
}

/** One stylised heartbeat across a motif, x in 0..1 of its width and y up in -1..1. */
private val PulsePoints = listOf(
    0f to 0f, 0.22f to 0f, 0.3f to 0.2f, 0.36f to 0f, 0.42f to -0.25f,
    0.5f to 1f, 0.58f to -0.6f, 0.64f to 0f, 0.74f to 0.28f, 0.82f to 0f, 1f to 0f,
)

/** A short bump centred at [at] (seconds) on a heartbeat cycle. */
private fun bump(t: Float, at: Float): Float {
    val d = (t - at) / 0.12f
    return if (d < -1f || d > 1f) 0f else cos(d * PI.toFloat() / 2f)
}

/** Hour and minute hands: the minute hand sweeps a turn a minute, the hour hand twelve times slower. */
private fun DrawScope.drawHands(center: Offset, length: Float, color: Color, alpha: Float, time: Float, phase: Float) {
    val minutes = phase * 10f + time / 60f
    val width = (length * 0.12f).coerceAtLeast(2f)
    fun hand(turns: Float, reach: Float) {
        val angle = turns * 2f * PI.toFloat() - PI.toFloat() / 2f
        drawLine(
            color,
            center,
            Offset(center.x + cos(angle) * reach, center.y + sin(angle) * reach),
            strokeWidth = width,
            cap = StrokeCap.Round,
            alpha = alpha * 1.4f,
        )
    }
    hand(minutes / 12f, length * 0.6f)
    hand(minutes, length)
    drawCircle(color, width, center, alpha = alpha * 1.4f)
}
