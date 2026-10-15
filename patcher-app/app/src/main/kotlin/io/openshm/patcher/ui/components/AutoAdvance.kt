package io.openshm.patcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import io.openshm.patcher.R
import io.openshm.patcher.data.Automation

/** How long the bar of an advance button takes to fill before the step continues by itself. */
const val AUTO_ADVANCE_MILLIS = 2_000

/**
 * Shared state of the unattended countdowns. A touch or scroll while a countdown is filling pauses
 * the current step (`paused`); the user then continues by hand. Leaving the step ([onRouteChanged])
 * lifts the pause, so the next steps advance by themselves again.
 */
class AutoAdvanceController {
    private val pausedState = mutableStateOf(false)

    /** The current step's countdown is paused by the user. Observable from Compose. */
    val paused: Boolean get() = pausedState.value

    /** Number of countdowns filling right now. Touches only matter while this is above zero. */
    var counting: Int = 0
        private set

    private var routeKey: Any? = null

    fun beginCountdown() {
        counting++
    }

    fun endCountdown() {
        if (counting > 0) counting--
    }

    /** Called for every touch or scroll; pauses only if a countdown is filling. */
    fun onUserInput() {
        if (counting > 0) pausedState.value = true
    }

    /** Called with the current destination identity; a different one lifts the pause. */
    fun onRouteChanged(key: Any?) {
        if (key == routeKey) return
        routeKey = key
        pausedState.value = false
    }
}

/** The controller of the nav host; null where the feature is off (previews, Compose Driver). */
val LocalAutoAdvance = staticCompositionLocalOf<AutoAdvanceController?> { null }

/**
 * Reports every press and scroll to [controller] without consuming anything. Window-based content
 * (dialogs) does not pass through the root, so it applies this too.
 */
fun Modifier.observeUserInput(controller: AutoAdvanceController?): Modifier =
    if (controller == null) this else pointerInput(controller) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) controller.onUserInput()
            }
        }
    }

/** [observeUserInput] for the controller provided by the nav host (no-op without one). */
@Composable
fun Modifier.observeUserInput(): Modifier = observeUserInput(LocalAutoAdvance.current)

/**
 * Runs the countdown while [active] and the step is not paused and the screen is resumed, then calls
 * [onFire] once. Returns the progress 0..1 while it is filling, null otherwise. Leaving RESUMED
 * cancels it; it restarts from zero on return. With "Advance freely" on and an unattended run going
 * there is no countdown: [onFire] runs as soon as the button is armed.
 */
@Composable
fun rememberAutoAdvance(active: Boolean, onFire: () -> Unit): Float? {
    val controller = LocalAutoAdvance.current
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val running = active && controller != null && !controller.paused && resumed
    // Only inside an unattended run: a step opened from Home (a rebuild, a fix) counts down instead.
    val instant = Automation.state.collectAsState().value.freeRunActive &&
        UnattendedRun.state.collectAsState().value == RunState.Running
    val progress = remember { Animatable(0f) }
    var fired by remember { mutableStateOf(false) }
    val fire by rememberUpdatedState(onFire)
    // Fired once per activation: a button that stays after firing does not fire again until re-armed.
    LaunchedEffect(active) { if (!active) fired = false }
    LaunchedEffect(running, fired, instant) {
        if (!running || fired || controller == null) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        controller.beginCountdown()
        try {
            progress.snapTo(0f)
            if (!instant) progress.animateTo(1f, tween(AUTO_ADVANCE_MILLIS, easing = LinearEasing))
            fired = true
            fire()
        } finally {
            controller.endCountdown()
        }
    }
    return if (running && !fired) progress.value else null
}

/** Draws the countdown fill (left to right) over a pill; [progress] null draws nothing. */
fun Modifier.autoFill(progress: Float?, color: Color): Modifier =
    if (progress == null) this else drawWithContent {
        drawContent()
        drawRect(color, size = Size(size.width * progress.coerceIn(0f, 1f), size.height))
    }

/** The fill tone for a button whose content colour is [content]: a low-alpha overlay of it. */
internal fun autoFillColor(content: Color): Color = content.copy(alpha = 0.28f)

/**
 * Text button for dialog and overlay confirmations. With [auto] it fills like [PrimaryAction] and
 * calls [onClick] when the bar is full. The caller decides [auto] (it never reads the settings itself)
 * and applies [observeUserInput] to its dialog content.
 */
@Composable
fun AutoTextButton(
    text: String,
    onClick: () -> Unit,
    auto: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    AutoTextButtonContent(text, onClick, rememberAutoAdvance(auto, onClick), modifier, color)
}

/** Pure rendering of [AutoTextButton] for a given [progress] (null: no bar). */
@Composable
internal fun AutoTextButtonContent(
    text: String,
    onClick: () -> Unit,
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val description = stringResource(R.string.auto_advance_state)
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .autoFill(progress, autoFillColor(color).copy(alpha = 0.18f))
            .semantics { if (progress != null) stateDescription = description },
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
