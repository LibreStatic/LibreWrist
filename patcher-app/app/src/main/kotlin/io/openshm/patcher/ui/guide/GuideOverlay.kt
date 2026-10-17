package io.openshm.patcher.ui.guide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openshm.patcher.R
import io.openshm.patcher.device.guide.ControlStrip
import io.openshm.patcher.device.guide.GuideUi
import io.openshm.patcher.device.guide.PetMood
import io.openshm.patcher.device.guide.PetPlacement
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val Amber = Color(0xFFFFB300)
private val Smoke = Color(0xFFE9E9F0)
private val SmokeShade = Color(0xFFC9CAD6)

// Farewell timeline (ms since it started), as in the design.
private const val FW_HAPPY = 300L
// The last word stays up ~3.5 s before the bow, long enough to read.
private const val FW_BOW = 3800L
private const val FW_STRAIGHT = 4700L
private const val FW_SMOKE = 4950L
private const val FW_END = 7500L

private const val HIGHLIGHT_PAD_DP = 4f

/**
 * Everything the guide draws over the screen, from one [GuideUi]: the blinking box, Tic's reaching
 * arm and glove, the bubble, Tic itself, the control strip and the farewell. Full-screen and
 * transparent; only Tic while asleep ([onPetTap]) and the code boxes take touches. [GuideUi.target]
 * is in screen pixels, [screenOffset] is where this overlay's origin sits on the screen.
 * [imeHeightPx] overrides the keyboard height read from the window insets, [topInsetPx] the status bar
 * (an accessibility overlay gets no insets of its own, and the bar may hold a camera cutout).
 */
@Composable
fun GuideOverlay(
    ui: GuideUi,
    onPetTap: () -> Unit,
    onCodeEntered: (String) -> Unit,
    modifier: Modifier = Modifier,
    screenOffset: IntOffset = IntOffset.Zero,
    imeHeightPx: Int? = null,
    onCodeDismiss: () -> Unit = {},
    topInsetPx: Int? = null,
) {
    var farewellMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.farewell) {
        farewellMs = 0L
        if (!ui.farewell) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (farewellMs < FW_END) farewellMs = withFrameMillis { it } - start
    }
    GuideOverlayContent(ui, farewellMs, onPetTap, onCodeEntered, modifier, screenOffset, imeHeightPx, onCodeDismiss, topInsetPx)
}

/** [GuideOverlay] with the farewell clock passed in, so previews can freeze it at a chosen moment. */
@Composable
internal fun GuideOverlayContent(
    ui: GuideUi,
    farewellMs: Long,
    onPetTap: () -> Unit,
    onCodeEntered: (String) -> Unit,
    modifier: Modifier = Modifier,
    screenOffset: IntOffset = IntOffset.Zero,
    imeHeightPx: Int? = null,
    onCodeDismiss: () -> Unit = {},
    topInsetPx: Int? = null,
) {
    // Dropping out of the composition when hidden makes Tic walk in again the next time.
    if (!ui.visible) return
    val still = isStill()
    val colors = GuideColors(isSystemInDarkTheme())
    val density = LocalDensity.current
    val d = density.density
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    val statusTop = topInsetPx ?: WindowInsets.statusBars.getTop(density)
    val imeInset = imeHeightPx ?: WindowInsets.ime.getBottom(density)

    val fw = if (ui.farewell) farewellMs else -1L
    val petGone = fw >= FW_SMOKE
    val bowing = fw in FW_BOW until FW_STRAIGHT
    val bubbleHidden = fw >= FW_BOW
    val mood = when {
        fw >= FW_HAPPY -> PetMood.Happy
        else -> ui.mood
    }

    val pad = HIGHLIGHT_PAD_DP * d
    val target = ui.target?.takeIf { !ui.farewell }?.let {
        Rect(
            (it.left - screenOffset.x).toFloat(), (it.top - screenOffset.y).toFloat(),
            (it.right - screenOffset.x).toFloat(), (it.bottom - screenOffset.y).toFloat(),
        )
    }

    var stripHeight by remember { mutableIntStateOf(0) }
    var bubbleSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val stripShown = if (ui.strip != ControlStrip.Hidden) (if (stripHeight > 0) stripHeight else (40 * d).roundToInt()) else 0
        val keyboardTop = if (ui.placement == PetPlacement.AboveKeyboard) {
            h - (if (imeInset > 0) imeInset.toFloat() else h * .4f)
        } else null
        val area = GuideArea(
            width = w, height = h, density = d,
            top = maxOf(statusTop.toFloat(), 24 * d) + stripShown + 2 * d,
            bottom = h - navBottom,
            keyboardTop = keyboardTop,
        )
        val bubble = ui.bubble
        val bw = minOf(236 * d, w - 16 * d)
        val bh = if (bubble != null) bubbleSize.height.toFloat() else 0f
        val placement = if (ui.farewell) PetPlacement.Corner else ui.placement
        val spot = guideSpot(area, placement, target, bw, bh)

        // ---- Tic walks between spots (~0.76 s), or snaps when motion is off.
        val petPos = remember { Animatable(spot.pet, Offset.VectorConverter) }
        var placed by remember { mutableStateOf(false) }
        var walking by remember { mutableStateOf(false) }
        LaunchedEffect(spot.pet, still) {
            if (!placed) {
                if (still) petPos.snapTo(spot.pet) else petPos.snapTo(Offset(w + 20 * d, spot.pet.y))
                placed = true
            }
            if (still || (petPos.value - spot.pet).getDistance() < 1f) {
                petPos.snapTo(spot.pet)
                return@LaunchedEffect
            }
            walking = true
            try {
                petPos.animateTo(spot.pet, tween(760, easing = FastOutSlowInEasing))
            } finally {
                walking = false
            }
        }

        // ---- Arms aim at the target from the shoulder on its side.
        val k = area.petWidth / 100f
        val destination = spot.pet
        val targetOnLeft = target != null && target.center.x <= destination.x + 50 * k
        var armLeft = ARM_REST_LEFT
        var armRight = ARM_REST_RIGHT
        if (mood == PetMood.Sleep) {
            armLeft = 100f; armRight = 80f
        } else if (fw >= FW_BOW) {
            armLeft = 70f; armRight = 110f
        } else if (fw >= FW_HAPPY) {
            armLeft = 150f; armRight = 30f
        } else if (target != null) {
            val side = if (targetOnLeft) ShoulderLeft else ShoulderRight
            val sx = destination.x + side.x * k
            val sy = destination.y + side.y * k
            val angle = Math.toDegrees(atan2((target.center.y - sy).toDouble(), (target.center.x - sx).toDouble())).toFloat()
            if (targetOnLeft) armLeft = angle else armRight = angle
        }
        val reaching = ui.reach && target != null
        val hiddenArm = if (reaching) (if (targetOnLeft) ArmSide.Left else ArmSide.Right) else null

        // ---- Highlight box: only on the user's own steps.
        val lastTarget = remember { object { var rect = Rect.Zero } }
        val highlightRect = target ?: lastTarget.rect
        SideEffect { if (target != null) lastTarget.rect = target }
        val hlBox = animateRectAsState(
            Rect(highlightRect.left - pad, highlightRect.top - pad, highlightRect.right + pad, highlightRect.bottom + pad),
            if (still) snap() else tween(350),
            label = "highlight",
        )
        val hlAlpha = animateFloatAsState(if (ui.highlight && target != null) 1f else 0f, if (still) snap() else tween(200), label = "hlAlpha")
        val blink = if (still) null else rememberInfiniteTransition(label = "blink").animateFloat(
            0f, 1f, infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "blink",
        )

        // ---- Glove and the arm that reaches for it.
        val reach = animateFloatAsState(if (reaching) 1f else 0f, if (still) snap() else tween(400, easing = LinearOutSlowInEasing), label = "reach")
        val fingerAlpha = animateFloatAsState(if (reaching) 1f else 0f, if (still) snap() else tween(200), label = "finger")
        val press = remember { Animatable(0f) }
        val ripple = remember { Animatable(1f) }
        var seenPress by remember { mutableIntStateOf(ui.pressSeq) }
        LaunchedEffect(ui.pressSeq, still) {
            if (ui.pressSeq == seenPress) return@LaunchedEffect
            seenPress = ui.pressSeq
            if (still) return@LaunchedEffect
            press.snapTo(0f)
            ripple.snapTo(0f)
            // One press (0.3 s) with the ripple (0.5 s) running alongside.
            coroutineScope {
                launch { ripple.animateTo(1f, tween(500, easing = LinearOutSlowInEasing)) }
                press.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
            }
        }

        // ---- Layer 1: highlight and the pressed target.
        Canvas(Modifier.fillMaxSize()) {
            if (hlAlpha.value > 0.01f) {
                drawHighlight(hlBox.value, hlAlpha.value, blink?.value)
            }
            if (target != null && ripple.value < 1f) {
                val r = ripple.value
                val fade = 1f - r
                drawRoundRect(
                    colors.ripple.copy(alpha = .14f * fade), Offset(target.left, target.top), Size(target.width, target.height),
                    CornerRadius(12 * d),
                )
                drawCircle(colors.ripple.copy(alpha = .35f * fade), 22 * d * (.2f + 1.4f * r), target.center)
            }
        }

        // ---- Layer 2: control strip.
        ControlStripBar(
            ui.strip, colors, still,
            Modifier.align(Alignment.TopCenter).padding(top = with(density) { statusTop.toDp() }).onSizeChanged { if (it.height > 0) stripHeight = it.height },
        )

        // ---- Layer 3: bubble. Hidden while Tic walks, and once the farewell bow starts.
        if (bubble != null && placed) {
            val show = remember { Animatable(0f) }
            val visible = !walking && !bubbleHidden
            LaunchedEffect(visible, bubble, still) {
                if (still) show.snapTo(if (visible) 1f else 0f)
                else if (visible) {
                    if (show.value >= 1f) show.snapTo(0f)
                    show.animateTo(1f, tween(350, easing = CubicBezierEasing(.2f, 1.4f, .4f, 1f)))
                } else show.animateTo(0f, tween(180))
            }
            val (ox, oy) = spot.tail.origin()
            TicBubble(
                bubble, spot.tail, colors, onCodeEntered, onCodeDismiss,
                Modifier
                    .width(with(density) { bw.toDp() })
                    .offset { IntOffset(spot.bubble.x.roundToInt(), spot.bubble.y.roundToInt()) }
                    .onSizeChanged { bubbleSize = it }
                    .graphicsLayer {
                        val v = show.value
                        alpha = v.coerceIn(0f, 1f)
                        scaleX = .6f + .4f * v
                        scaleY = .6f + .4f * v
                        transformOrigin = TransformOrigin(ox, oy)
                    },
            )
        }

        // ---- Layer 4: Tic.
        if (placed && !petGone) {
            val sleeping = ui.placement == PetPlacement.Sleeping || ui.mood == PetMood.Sleep
            val description = stringResource(R.string.guide_ui_pet_description)
            val walkMood = if (walking && mood == PetMood.Talk) PetMood.Idle else mood
            Box(
                Modifier
                    .offset { IntOffset(petPos.value.x.roundToInt(), petPos.value.y.roundToInt()) }
                    .size(PET_WIDTH_DP.dp, PET_HEIGHT_DP.dp)
                    .semantics { contentDescription = description }
                    .let { if (sleeping) it.clickable(role = Role.Button, onClick = onPetTap) else it },
            ) {
                GuidePet(
                    walkMood, Modifier.fillMaxSize(), armLeft, armRight, hiddenArm,
                    walking = walking, bow = bowing,
                )
            }
        }

        // ---- Layer 5: the reaching arm with its glove, and the smoke.
        Canvas(Modifier.fillMaxSize()) {
            if (target != null && (fingerAlpha.value > 0.01f || reach.value > 0.01f)) {
                drawReach(petPos.value, k, targetOnLeft, target, reach.value, fingerAlpha.value, press.value)
            }
            if (fw >= FW_SMOKE) drawSmoke(spot.pet, area, (fw - FW_SMOKE), still)
        }
    }
}

private fun DrawScope.drawHighlight(box: Rect, alpha: Float, blink: Float?) {
    val d = density
    val radius = CornerRadius(14 * d)
    // Blinking: border fades to 25%, the glow ring spreads 7 dp and fades, the tint fades out.
    // Motion off: a steady border with a 4 dp ring.
    val f = blink ?: 0f
    val tint = if (blink == null) .10f else .10f * (1f - f)
    val borderAlpha = if (blink == null) 1f else 1f - .75f * f
    val ring = if (blink == null) 4 * d else 7 * d * f
    val ringAlpha = if (blink == null) .45f else .45f * (1f - f)
    drawRoundRect(Amber.copy(alpha = tint * alpha), box.topLeft, box.size, radius)
    if (ring > 0f) {
        drawRoundRect(
            Amber.copy(alpha = ringAlpha * alpha), Offset(box.left - ring / 2, box.top - ring / 2),
            Size(box.width + ring, box.height + ring), CornerRadius(14 * d + ring / 2), style = Stroke(ring),
        )
    }
    val w = 3 * d
    drawRoundRect(
        Amber.copy(alpha = borderAlpha * alpha), Offset(box.left + w / 2, box.top + w / 2),
        Size(box.width - w, box.height - w), CornerRadius(14 * d - w / 2), style = Stroke(w),
    )
}

/**
 * Tic's arm on the target's side leaves its body and stretches in a slight curve to the cuff of a
 * big glove, whose fingertip lands on the target centre. The press shrinks and nudges the glove.
 */
private fun DrawScope.drawReach(
    pet: Offset,
    k: Float,
    left: Boolean,
    target: Rect,
    progress: Float,
    alpha: Float,
    press: Float,
) {
    val d = density
    val shoulder = (if (left) ShoulderLeft else ShoulderRight).let { Offset(pet.x + it.x * k, pet.y + it.y * k) }
    // The glove (34x42 dp, fingertip at 12,2; cuff centre at 17.5,40) starts with its cuff on the shoulder.
    val start = Offset(shoulder.x - 17.5f * d, shoulder.y - 40f * d)
    val end = Offset(target.center.x - 12f * d, target.center.y - 2f * d)
    val pos = Offset(start.x + (end.x - start.x) * progress, start.y + (end.y - start.y) * progress)
    val cuff = Offset(pos.x + 17.5f * d, pos.y + 40f * d)
    val dx = cuff.x - shoulder.x
    val dy = cuff.y - shoulder.y
    val bend = if (left) -.18f else .18f
    val arm = Path().apply {
        moveTo(shoulder.x, shoulder.y)
        quadraticTo((shoulder.x + cuff.x) / 2 - dy * bend, (shoulder.y + cuff.y) / 2 + dx * bend, cuff.x, cuff.y)
    }
    drawPath(arm, PetBezel.copy(alpha = alpha), style = Stroke(4 * d, cap = StrokeCap.Round))
    val p = sin(PI * press).toFloat()
    translate(pos.x, pos.y) {
        scale(d, Offset.Zero) {
            scale(1f - .16f * p, Offset(12f, 2f)) {
                translate(0f, 3f * p) { drawGlove(alpha) }
            }
        }
    }
}

private fun DrawScope.drawGlove(alpha: Float) {
    val glove = PetGlove.copy(alpha = alpha)
    val line = PetBezel.copy(alpha = alpha)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color, stroke: Float) {
        drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(r))
        drawRoundRect(line, Offset(x, y), Size(w, h), CornerRadius(r), style = Stroke(stroke))
    }
    box(8f, 1f, 8.5f, 24f, 4.25f, glove, 1.8f)
    box(5f, 16f, 25f, 20f, 8f, glove, 1.8f)
    drawLine(line, Offset(17f, 21f), Offset(17f, 27f), 1.4f, StrokeCap.Round)
    drawLine(line, Offset(22.5f, 21f), Offset(22.5f, 27f), 1.4f, StrokeCap.Round)
    box(7f, 34f, 21f, 7f, 3f, PetStrap.copy(alpha = alpha), 1.6f)
}

private class Puff(val size: Float, val angle: Float, val dist: Float, val durationMs: Float)

private val Puffs = Random(7).let { r ->
    List(14) { i ->
        Puff(22f + r.nextFloat() * 26f, i / 14f * 2f * PI.toFloat() + r.nextFloat() * .5f, 18f + r.nextFloat() * 30f, 900f + r.nextFloat() * 400f)
    }
}

/** Puffs of smoke where Tic stood, [ms] after they started. */
private fun DrawScope.drawSmoke(pet: Offset, area: GuideArea, ms: Long, still: Boolean) {
    val d = density
    val center = Offset(pet.x + area.petWidth / 2, pet.y + area.petHeight / 2 + 6 * d)
    val puffs = if (still) Puffs.take(5) else Puffs
    for (p in puffs) {
        val t = (ms / p.durationMs).coerceIn(0f, 1f)
        if (t >= 1f) continue
        val e = CubicBezierEasing(.2f, .7f, .3f, 1f).transform(t)
        // Keyframes: 0 -> 45% (70% of the way, grown) -> 100% (out, 16 dp higher, gone).
        val first = e < .45f
        val local = if (first) e / .45f else (e - .45f) / .55f
        val ex = cos(p.angle) * p.dist
        val ey = sin(p.angle) * p.dist - 14f
        val offX = if (first) ex * .7f * local else ex * (.7f + .3f * local)
        val offY = if (first) ey * .7f * local else ey * .7f + (ey - 16f - ey * .7f) * local
        val sc = if (first) .3f + .8f * local else 1.1f + .4f * local
        val a = if (first) .95f - .05f * local else .9f * (1f - local)
        val radius = p.size * d / 2 * sc
        val c = Offset(center.x + offX * d, center.y + offY * d)
        drawCircle(
            Brush.radialGradient(
                listOf(Smoke.copy(alpha = a), SmokeShade.copy(alpha = a)),
                center = Offset(c.x - radius * .3f, c.y - radius * .3f), radius = radius * 1.85f,
            ),
            radius, c,
        )
    }
}

@Composable
private fun ControlStripBar(strip: ControlStrip, colors: GuideColors, still: Boolean, modifier: Modifier) {
    val last = remember { object { var value = ControlStrip.Controlling } }
    if (strip != ControlStrip.Hidden) last.value = strip
    val shown = if (strip != ControlStrip.Hidden) strip else last.value
    val controlling = shown == ControlStrip.Controlling
    AnimatedVisibility(
        visible = strip != ControlStrip.Hidden,
        modifier = modifier,
        enter = if (still) EnterTransition.None else slideInVertically(tween(250)) { -it },
        exit = if (still) ExitTransition.None else slideOutVertically(tween(250)) { -it },
    ) {
        val background = if (controlling) colors.controlling else MaterialTheme.colorScheme.primaryContainer
        val content = if (controlling) colors.onControlling else MaterialTheme.colorScheme.onPrimaryContainer
        val pulse = if (still || !controlling) null else rememberInfiniteTransition(label = "rec").animateFloat(
            1f, .25f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rec",
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .background(background)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = pulse?.value ?: 1f }
                    .background(content, if (controlling) CircleShape else RoundedCornerShape(2.dp)),
            )
            Text(
                stringResource(if (controlling) R.string.guide_ui_strip_controlling else R.string.guide_ui_strip_paused),
                style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, color = content, textAlign = TextAlign.Center),
            )
        }
    }
}
