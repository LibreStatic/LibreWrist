package io.openshm.patcher.ui.guide

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.openshm.patcher.device.guide.PetMood
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.rememberReducedMotion

// Tic is a physical object: the same colors in light and dark.
internal val PetBezel = Color(0xFF2B2D3A)
internal val PetScreen = Color(0xFF3D5AFE)
private val PetScreenSad = Color(0xFF5D6694)
private val PetScreenSleep = Color(0xFF27348F)
private val PetInk = Color.White
internal val PetStrap = Color(0xFFFF8A65)
private val PetStrapLine = Color(0xFFD9673F)
private val PetCheek = Color(0xFFFF9EBB)
internal val PetGlove = Color.White
private val PetTear = Color(0xFF8FD3FF)

/** Arm angles in degrees (0 = pointing right, as in the design's SVG). */
internal const val ARM_REST_LEFT = 112f
internal const val ARM_REST_RIGHT = 68f

/** Shoulder positions in the 100x130 view box. */
internal val ShoulderLeft = Offset(21f, 64f)
internal val ShoulderRight = Offset(79f, 66f)

enum class ArmSide { Left, Right }

/** True when the user turned animations off, or the host asked for a still frame. */
@Composable
internal fun isStill(): Boolean = LocalReducedMotion.current || rememberReducedMotion()

/**
 * Tic, drawn with paths in a 100x130 view box scaled to [modifier]'s size (84x109 dp by default).
 * [armLeft] and [armRight] are the arm angles; [hiddenArm] leaves one arm out because the overlay
 * draws it stretched to the glove instead. [walking] steps the legs, [bow] bends forward.
 */
@Composable
fun GuidePet(
    mood: PetMood,
    modifier: Modifier = Modifier.size(PET_WIDTH_DP.dp, PET_HEIGHT_DP.dp),
    armLeft: Float = ARM_REST_LEFT,
    armRight: Float = ARM_REST_RIGHT,
    hiddenArm: ArmSide? = null,
    walking: Boolean = false,
    bow: Boolean = false,
) {
    val still = isStill()
    val left = rememberArmAngle(armLeft, !still)
    val right = rememberArmAngle(armRight, !still)

    // Body pose per mood; a springy overshoot like the design's cubic-bezier(.3,1.3,.5,1).
    val tiltTarget = if (bow) -30f else when (mood) { PetMood.Sad -> 5f; PetMood.Sleep -> 9f; else -> 0f }
    val dyTarget = if (bow) 2f else when (mood) { PetMood.Sad -> 3f; PetMood.Sleep -> 5f; else -> 0f }
    val scaleTarget = if (bow) 1f else when (mood) { PetMood.Sad -> .97f; PetMood.Sleep -> .95f; else -> 1f }
    val poseSpec = if (still) snap<Float>() else tween(450, easing = CubicBezierEasing(.3f, 1.3f, .5f, 1f))
    val tilt by animateFloatAsState(tiltTarget, poseSpec, label = "tilt")
    val dy by animateFloatAsState(dyTarget, poseSpec, label = "dy")
    val bodyScale by animateFloatAsState(scaleTarget, poseSpec, label = "scale")
    val screen by animateColorAsState(
        when (mood) { PetMood.Sad -> PetScreenSad; PetMood.Sleep -> PetScreenSleep; else -> PetScreen },
        if (still) snap() else tween(400),
        label = "screen",
    )

    val loops = if (still) null else rememberLoops()
    val measurer = rememberTextMeasurer()

    Canvas(modifier) {
        val s = size.width / 100f
        scale(s, Offset.Zero) {
            val bob = when {
                loops == null -> 0f
                walking -> loops.hop.value
                mood == PetMood.Sleep -> 0f
                else -> loops.bob.value
            }
            val breathe = if (loops != null && mood == PetMood.Sleep) loops.breathe.value else 1f
            val look = if (loops != null && mood == PetMood.Search && !bow) loops.look.value else 0f
            val swing = if (loops != null && walking) loops.step.value else 0f
            translate(0f, bob) {
                scale(1f, breathe, Offset(50f, 120f)) {
                    drawLeg(39f, 42f, swing)
                    drawLeg(61f, 58f, -swing)
                    translate(0f, dy) {
                        rotate(tilt + look, Offset(50f, 104f)) {
                            scale(1f, bodyScale, Offset(50f, 104f)) {
                                drawBody(mood, screen, left.value, right.value, hiddenArm, loops, measurer)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Every endless animation of the pet; created only when motion is allowed. */
private class PetLoops(
    val bob: State<Float>, val hop: State<Float>, val step: State<Float>, val breathe: State<Float>, val look: State<Float>,
    val blink: State<Float>, val talk: State<Float>, val scan: State<Float>, val tear: State<Float>,
    val zzz1: State<Float>, val zzz2: State<Float>,
)

@Composable
private fun rememberLoops(): PetLoops {
    val t = rememberInfiniteTransition(label = "tic")
    @Composable fun bounce(from: Float, to: Float, ms: Int) = t.animateFloat(
        from, to, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bounce",
    )
    val bob = bounce(0f, -2.5f, 1200)
    val hop = bounce(0f, -3f, 360)
    val step = bounce(-24f, 24f, 360)
    val breathe = bounce(1f, .965f, 1700)
    val look = bounce(-4f, 4f, 800)
    val talk = bounce(.35f, 1.15f, 220)
    val scan = bounce(-1.6f, 1.6f, 800)
    val blink = t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 4200; 1f at 3950; .1f at 4075; 1f at 4200 }),
        label = "blink",
    )
    val tear = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = CubicBezierEasing(.42f, 0f, 1f, 1f))), label = "tear")
    val zzz1 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing)), label = "z1")
    val zzz2 = t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), initialStartOffset = StartOffset(900)), label = "z2",
    )
    return PetLoops(bob, hop, step, breathe, look, blink, talk, scan, tear, zzz1, zzz2)
}

@Composable
private fun rememberArmAngle(target: Float, animate: Boolean): State<Float> {
    val angle = remember { Animatable(target) }
    LaunchedEffect(target, animate) {
        // Turn the short way round.
        var delta = (target - angle.value) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        if (animate) angle.animateTo(angle.value + delta, tween(420, easing = CubicBezierEasing(.33f, 1f, .68f, 1f)))
        else angle.snapTo(angle.value + delta)
    }
    return angle.asState()
}

private fun DrawScope.drawLeg(footX: Float, x: Float, swingDeg: Float) {
    rotate(swingDeg, Offset(footX, 98f)) {
        drawLine(PetBezel, Offset(x, 98f), Offset(x, 117f), 5f, StrokeCap.Round)
        drawOval(PetStrap, Offset(footX - 7f, 115.5f), Size(14f, 8f))
    }
}

private fun DrawScope.roundRect(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r, r))

private fun DrawScope.strap(y: Float, h: Float, lineA: Float, lineB: Float) {
    roundRect(PetStrap, 35f, y, 30f, h, 7f)
    drawLine(PetStrapLine, Offset(39f, lineA), Offset(61f, lineA), 1.6f, StrokeCap.Round)
    drawLine(PetStrapLine, Offset(39f, lineB), Offset(61f, lineB), 1.6f, StrokeCap.Round)
}

private fun DrawScope.drawBody(
    mood: PetMood,
    screen: Color,
    armLeft: Float,
    armRight: Float,
    hiddenArm: ArmSide?,
    loops: PetLoops?,
    measurer: TextMeasurer,
) {
    strap(7f, 24f, 14f, 21f)
    strap(80f, 21f, 89f, 95f)
    roundRect(PetBezel, 79f, 49f, 6f, 11f, 2f)
    drawCircle(PetBezel, 30f, Offset(50f, 55f))
    drawCircle(screen, 24f, Offset(50f, 55f))
    // Screen glint: the arc from (33,42) to (48,33) of the radius 22 circle.
    drawArc(
        Color.White.copy(alpha = .28f), startAngle = 217f, sweepAngle = 47f, useCenter = false,
        topLeft = Offset(50f - 22f, 55f - 22f), size = Size(44f, 44f), style = Stroke(3f, cap = StrokeCap.Round),
    )
    if (mood != PetMood.Sad) {
        drawCircle(PetCheek.copy(alpha = .7f), 3f, Offset(35.5f, 61f))
        drawCircle(PetCheek.copy(alpha = .7f), 3f, Offset(64.5f, 61f))
    }
    drawFace(mood, loops, measurer)
    if (hiddenArm != ArmSide.Left) drawArm(ShoulderLeft, armLeft)
    if (hiddenArm != ArmSide.Right) drawArm(ShoulderRight, armRight)
}

private fun DrawScope.drawEyes(blink: Float) {
    for (x in floatArrayOf(42f, 58f)) {
        scale(1f, blink, Offset(x, 52f)) { drawOval(PetInk, Offset(x - 3.8f, 52f - 4.8f), Size(7.6f, 9.6f)) }
    }
}

private fun DrawScope.drawFace(mood: PetMood, loops: PetLoops?, measurer: TextMeasurer) {
    val line = { w: Float -> Stroke(w, cap = StrokeCap.Round) }
    when (mood) {
        PetMood.Idle, PetMood.Point -> {
            drawEyes(loops?.blink?.value ?: 1f)
            drawPath(Path().apply { moveTo(44.5f, 61.5f); quadraticTo(50f, 66f, 55.5f, 61.5f) }, PetInk, style = line(2.4f))
        }
        PetMood.Talk -> {
            drawEyes(loops?.blink?.value ?: 1f)
            scale(1f, loops?.talk?.value ?: 1f, Offset(50f, 62.5f)) { drawOval(PetInk, Offset(46.4f, 59.5f), Size(7.2f, 6f)) }
        }
        PetMood.Happy -> {
            drawPath(Path().apply { moveTo(38f, 54f); quadraticTo(42f, 48f, 46f, 54f) }, PetInk, style = line(2.6f))
            drawPath(Path().apply { moveTo(54f, 54f); quadraticTo(58f, 48f, 62f, 54f) }, PetInk, style = line(2.6f))
            drawPath(Path().apply { moveTo(43f, 60.5f); quadraticTo(50f, 69f, 57f, 60.5f); close() }, PetInk, style = Fill)
        }
        PetMood.Search -> {
            drawEyes(loops?.blink?.value ?: 1f)
            translate(loops?.scan?.value ?: 0f, 0f) {
                drawCircle(PetBezel, 2f, Offset(42f, 52.6f))
                drawCircle(PetBezel, 2f, Offset(58f, 52.6f))
            }
            drawCircle(PetInk, 2.2f, Offset(50f, 63f))
        }
        PetMood.Sad -> {
            drawOval(PetInk, Offset(42f - 3.2f, 54f - 3.6f), Size(6.4f, 7.2f))
            drawOval(PetInk, Offset(58f - 3.2f, 54f - 3.6f), Size(6.4f, 7.2f))
            drawLine(PetInk, Offset(37f, 48.5f), Offset(45f, 46f), 2.2f, StrokeCap.Round)
            drawLine(PetInk, Offset(63f, 48.5f), Offset(55f, 46f), 2.2f, StrokeCap.Round)
            drawPath(Path().apply { moveTo(44.5f, 65f); quadraticTo(50f, 60f, 55.5f, 65f) }, PetInk, style = line(2.4f))
            // The tear falls 9 units and fades; a still frame shows it partway down.
            val p = loops?.tear?.value ?: .4f
            val alpha = if (p < .2f) p / .2f else 1f - (p - .2f) / .8f
            translate(0f, 9f * p) {
                drawPath(
                    Path().apply {
                        moveTo(39.5f, 58f); relativeQuadraticTo(-2.4f, 3.6f, 0f, 5f); relativeQuadraticTo(2.4f, -1.4f, 0f, -5f); close()
                    },
                    PetTear.copy(alpha = alpha.coerceIn(0f, 1f)),
                )
            }
        }
        PetMood.Sleep -> {
            drawPath(Path().apply { moveTo(37.5f, 56f); quadraticTo(42f, 59.5f, 46.5f, 56f) }, PetInk, style = line(2.4f))
            drawPath(Path().apply { moveTo(53.5f, 56f); quadraticTo(58f, 59.5f, 62.5f, 56f) }, PetInk, style = line(2.4f))
            drawOval(PetInk, Offset(47.8f, 63.5f), Size(4.4f, 3f))
            drawZ(measurer, "z", 11f, Offset(70f, 28f), loops?.zzz1?.value ?: .5f)
            drawZ(measurer, "Z", 15f, Offset(77f, 18f), loops?.zzz2?.value ?: .5f)
        }
    }
}

/** A floating letter: rises 7 units to the right and fades in and out (`zzz` keyframes). */
private fun DrawScope.drawZ(measurer: TextMeasurer, letter: String, size: Float, baseline: Offset, p: Float) {
    val alpha = when {
        p < .3f -> p / .3f
        p <= .7f -> 1f
        else -> (1f - p) / .3f
    }
    val x = 5f * p
    val y = 4f + (-7f - 4f) * p
    val layout = measurer.measure(letter, TextStyle(fontSize = size.toSp(), fontWeight = FontWeight.SemiBold, color = PetScreen.copy(alpha = alpha.coerceIn(0f, 1f))))
    drawText(layout, topLeft = Offset(baseline.x + x, baseline.y + y - layout.firstBaseline))
}

private fun DrawScope.drawArm(shoulder: Offset, angle: Float) {
    translate(shoulder.x, shoulder.y) {
        rotate(angle, Offset.Zero) {
            drawLine(PetBezel, Offset.Zero, Offset(19f, 0f), 4.5f, StrokeCap.Round)
            // The cuff of the glove and the round fist.
            drawRoundRect(PetGlove, Offset(23f, -2f), Size(10f, 4.2f), CornerRadius(2.1f, 2.1f))
            drawRoundRect(PetBezel, Offset(23f, -2f), Size(10f, 4.2f), CornerRadius(2.1f, 2.1f), style = Stroke(1.4f))
            drawCircle(PetGlove, 5.4f, Offset(22f, .5f))
            drawCircle(PetBezel, 5.4f, Offset(22f, .5f), style = Stroke(1.4f))
        }
    }
}
