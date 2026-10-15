package io.openshm.patcher.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.openshm.patcher.R
import io.openshm.patcher.build.BuildController
import io.openshm.patcher.build.BuildRunPhase
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.data.Automation
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.SetupFlow
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the unattended-run indicator shows: nothing, the centered intro, the corner badge, the
 * "needs your attention" dialog ([Attention]) or, once that is dismissed, the paused badge ([Paused]).
 */
enum class UnattendedPhase { None, Intro, Badge, Attention, Paused }

/** Setup steps after the welcome screen; the guided browser is the Acquire step's sub-screen. */
fun isUnattendedRunStep(route: Route?): Boolean =
    route != null && route != Route.Welcome && route != Route.Home &&
        (route == Route.GuidedBrowser || route in SetupFlow.order)

enum class RunState { Idle, Running }

/**
 * Transitions of an unattended run. It starts when "Advance freely" is on and the flow leaves Welcome
 * for a setup step, and ends (Idle) on reaching "All set" or Home, on [fail] or when the mode is switched off. The
 * route does not matter while it runs. Leaving Idle shows the intro; [introFinished] collapses it
 * into the badge; every return to Idle re-arms it. After a failure it only starts again from Welcome.
 *
 * A step that cannot go on without the user holds a token ([requestAttention]); while any is held the
 * run shows [UnattendedPhase.Attention], or [UnattendedPhase.Paused] once the user dismissed it. When
 * the last token goes, the run carries on with the badge.
 */
class UnattendedRunMachine {
    private val _state = MutableStateFlow(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()
    private val _phase = MutableStateFlow(UnattendedPhase.None)
    val phase: StateFlow<UnattendedPhase> = _phase.asStateFlow()
    private var previous: Route? = null
    private val attention = mutableSetOf<Any>()
    private var dismissed = false

    fun onRoute(freeRunActive: Boolean, route: Route?) {
        val from = previous
        previous = route
        if (!freeRunActive) return stop()
        when {
            // "All set" is the end of the run; Home too, for a flow that skips it.
            _state.value == RunState.Running -> if (route == Route.AllSet || route == Route.Home) stop()
            from == Route.Welcome && isUnattendedRunStep(route) -> {
                _state.value = RunState.Running
                _phase.value = UnattendedPhase.Intro
            }
        }
    }

    fun onFreeRun(active: Boolean) {
        if (!active) stop()
    }

    fun fail() = stop()

    fun introFinished() {
        if (_phase.value == UnattendedPhase.Intro) {
            _phase.value = UnattendedPhase.Badge
            refresh()
        }
    }

    fun requestAttention(token: Any) {
        if (attention.add(token) && attention.size == 1) dismissed = false
        refresh()
    }

    fun releaseAttention(token: Any) {
        attention.remove(token)
        refresh()
    }

    /** The user closed the dialog: keep waiting behind the paused badge. */
    fun dismissAttention() {
        dismissed = true
        refresh()
    }

    /** The paused badge was tapped: show the dialog again. */
    fun reopenAttention() {
        dismissed = false
        refresh()
    }

    private fun refresh() {
        // Neither the intro nor an idle machine changes here; introFinished calls back once it ends.
        if (_state.value != RunState.Running || _phase.value == UnattendedPhase.Intro) return
        _phase.value = when {
            attention.isEmpty() -> UnattendedPhase.Badge
            dismissed -> UnattendedPhase.Paused
            else -> UnattendedPhase.Attention
        }
    }

    private fun stop() {
        _state.value = RunState.Idle
        _phase.value = UnattendedPhase.None
        dismissed = false
    }
}

/** The process-wide run; screens report terminal failures with [fail] and waits with [UnattendedAttention]. */
object UnattendedRun {
    val machine = UnattendedRunMachine()
    val state: StateFlow<RunState> get() = machine.state
    fun fail() = machine.fail()
}

private const val INTRO_MILLIS = 2000L

/** How long a step must keep waiting for the user before the run asks for attention. */
const val ATTENTION_GRACE_MILLIS = 3000L

/**
 * Tells a running unattended run that this step cannot go on until the user acts, while [needed]
 * holds for longer than a short grace period (so a wait that resolves itself shows nothing). Leaving
 * the step, or [needed] turning false, lets the run continue. Outside a run it does nothing.
 * [graceMillis] is read when [needed] turns true; a step lengthens it while something that may still
 * resolve the wait by itself is in flight.
 */
@Composable
fun UnattendedAttention(needed: Boolean, graceMillis: Long = ATTENTION_GRACE_MILLIS) {
    val token = remember { Any() }
    val grace by rememberUpdatedState(graceMillis)
    LaunchedEffect(needed) {
        if (!needed) return@LaunchedEffect
        delay(grace)
        try {
            UnattendedRun.machine.requestAttention(token)
            awaitCancellation()
        } finally {
            UnattendedRun.machine.releaseAttention(token)
        }
    }
}

/** The phase for [routePath] (the destination's route pattern), driven by the free run preference. */
@Composable
fun rememberUnattendedPhase(routePath: String?): UnattendedPhase {
    val active = Automation.state.collectAsState().value.freeRunActive
    val machine = UnattendedRun.machine
    val phase = machine.phase.collectAsState().value
    // Failures of process-wide operations (the build, the phone's own ADB) end the run too, and so
    // does a build the user cancels: they took over, so nothing goes on by itself.
    val build = BuildController.state.collectAsState().value
    val buildFailed = build.phase == BuildRunPhase.Failed || build.phase == BuildRunPhase.Cancelled || build.cancelRequested
    val selfAdbFailed = SelfAdbController.state.collectAsState().value.phase == SelfAdbPhase.Failed
    LaunchedEffect(buildFailed, selfAdbFailed) { if (buildFailed || selfAdbFailed) machine.fail() }
    LaunchedEffect(active) { machine.onFreeRun(active) }
    LaunchedEffect(routePath) { machine.onRoute(Automation.state.value.freeRunActive, Route.entries.firstOrNull { it.path == routePath }) }
    LaunchedEffect(phase == UnattendedPhase.Intro) {
        if (phase == UnattendedPhase.Intro) {
            delay(INTRO_MILLIS)
            machine.introFinished()
        }
    }
    return phase
}

/** Draws the content at [saturation] (1 = untouched, no layer is created). */
fun Modifier.saturation(saturation: Float): Modifier =
    if (saturation >= 0.999f) this else drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply {
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
            }
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            canvas.restore()
        }
    }

/**
 * Half saturation plus a soft scrim while an unattended run is in [phase] other than
 * [UnattendedPhase.None]: a deeper one behind the intro, a faint one while only the badge shows.
 * Eased; snapped under reduced motion.
 */
@Composable
fun Modifier.unattendedSaturation(phase: UnattendedPhase): Modifier {
    val spec = if (LocalReducedMotion.current) tween<Float>(0) else tween(400)
    val saturation by animateFloatAsState(if (phase == UnattendedPhase.None) 1f else 0.5f, spec, label = "unattendedSaturation")
    val dim by animateFloatAsState(
        when (phase) {
            UnattendedPhase.None -> 0f
            UnattendedPhase.Intro -> INTRO_SCRIM
            else -> RUN_SCRIM
        },
        spec,
        label = "unattendedScrim",
    )
    val scrim = MaterialTheme.colorScheme.scrim
    return saturation(saturation).then(
        if (dim <= 0f) Modifier else Modifier.drawWithContent {
            drawContent()
            drawRect(scrim.copy(alpha = dim))
        },
    )
}

private const val INTRO_SCRIM = 0.32f
private const val RUN_SCRIM = 0.08f

private val BADGE_SIZE = 48.dp
private val BADGE_MARGIN = 16.dp
// Footer of StepScreenScaffold: 12 dp fade + 56 dp action + 16 dp padding, plus a gap.
private val BADGE_LIFT = 12.dp + 56.dp + 16.dp + 12.dp

/**
 * The intro (large fast-forward icon, title, subtitle) and, once collapsed, the corner badge; the
 * attention dialog while a step waits for the user, then the paused badge. Draws over the screen and
 * handles no input except a tap on the paused badge.
 */
@Composable
fun UnattendedRunOverlay(phase: UnattendedPhase, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val collapsed by animateFloatAsState(
        if (phase == UnattendedPhase.None || phase == UnattendedPhase.Intro) 0f else 1f,
        animationSpec = tween(if (reduced) 200 else 450),
        label = "unattendedCollapse",
    )
    val visible by animateFloatAsState(
        if (phase == UnattendedPhase.None) 0f else 1f,
        animationSpec = tween(200),
        label = "unattendedVisible",
    )
    // The badge steps aside while the attention dialog is up.
    val badgeShown by animateFloatAsState(
        if (phase == UnattendedPhase.Attention) 0f else 1f,
        animationSpec = tween(200),
        label = "unattendedBadgeShown",
    )
    if (phase == UnattendedPhase.Attention) UnattendedAttentionDialog(UnattendedRun.machine::dismissAttention)
    if (visible <= 0f && phase == UnattendedPhase.None) return
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        val density = LocalDensity.current
        val dx = with(density) { (maxWidth / 2 - BADGE_MARGIN - BADGE_SIZE / 2).toPx() }
        val dy = with(density) { (maxHeight / 2 - BADGE_LIFT - BADGE_SIZE / 2).toPx() }
        val introAlpha = (1f - collapsed) * visible
        if (introAlpha > 0f) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        alpha = introAlpha
                        if (!reduced) {
                            val scale = 1f - 0.8f * collapsed
                            scaleX = scale
                            scaleY = scale
                            translationX = dx * collapsed
                            translationY = dy * collapsed
                        }
                    }
                    .padding(24.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 28.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FastForwardIcon(Modifier.size(96.dp), MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.unattended_run_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        stringResource(R.string.unattended_run_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        val badgeAlpha = collapsed * visible * badgeShown
        if (badgeAlpha > 0f) {
            val paused = phase == UnattendedPhase.Paused
            val description = stringResource(if (paused) R.string.unattended_paused_badge else R.string.unattended_run_badge)
            val reopenLabel = stringResource(R.string.unattended_paused_reopen)
            val error = MaterialTheme.colorScheme.error
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = BADGE_MARGIN, bottom = BADGE_LIFT)
                    .graphicsLayer { alpha = badgeAlpha }
                    .size(BADGE_SIZE)
                    .shadow(4.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .then(
                        // Only the paused badge takes input: it brings the dismissed dialog back.
                        if (paused) {
                            Modifier.clickable(onClickLabel = reopenLabel, role = Role.Button) { UnattendedRun.machine.reopenAttention() }
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    paused -> {
                        CircularProgressIndicator(progress = { 1f }, modifier = Modifier.size(BADGE_SIZE), color = error, strokeWidth = 3.dp)
                        Icon(Icons.Rounded.Pause, contentDescription = null, tint = error, modifier = Modifier.size(24.dp))
                    }
                    else -> {
                        if (reduced) {
                            CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.size(BADGE_SIZE), strokeWidth = 3.dp)
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(BADGE_SIZE), strokeWidth = 3.dp)
                        }
                        FastForwardIcon(Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/**
 * "I need your attention": the run cannot go on until the user resolves what the current step
 * reports. [onDismiss] (the button, a tap outside or Back) leaves the paused badge behind.
 */
@Composable
private fun UnattendedAttentionDialog(onDismiss: () -> Unit) {
    val status = PatcherTheme.status
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("unattendedAttention"),
        icon = {
            Box(
                Modifier.size(56.dp).background(status.warningContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PriorityHigh, contentDescription = null, tint = status.warning, modifier = Modifier.size(32.dp))
            }
        },
        title = { Text(stringResource(R.string.unattended_attention_title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(R.string.unattended_attention_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.unattended_attention_ok)) }
        },
    )
}

/** Two right-pointing triangles that light up and slide right one after the other; static under reduced motion. */
@Composable
fun FastForwardIcon(modifier: Modifier, color: Color) {
    val t = if (LocalReducedMotion.current) null else {
        val transition = rememberInfiniteTransition(label = "fastForward")
        val value by transition.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "fastForwardPhase",
        )
        value
    }
    Canvas(modifier) {
        for (i in 0..1) drawTriangle(i, color, t)
    }
}

private fun DrawScope.drawTriangle(index: Int, color: Color, t: Float?) {
    // Distance in the cycle since this triangle was lit: it peaks at 0 and fades to a dim rest level.
    val since = if (t == null) 0f else ((t - index * 0.5f) % 1f + 1f) % 1f
    val lit = 1f - since
    val alpha = if (t == null) 1f else 0.3f + 0.7f * lit
    val width = size.width * 0.4f
    val x = size.width * (0.1f + 0.4f * index) + (if (t == null) 0f else size.width * 0.06f * lit)
    val top = size.height * 0.2f
    val bottom = size.height * 0.8f
    val path = Path().apply {
        moveTo(x, top)
        lineTo(x + width, size.height / 2)
        lineTo(x, bottom)
        close()
    }
    drawPath(path, color.copy(alpha = alpha))
}
