package io.openshm.patcher.ui.screens.welcome

import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.ui.nav.SkippedStep
import io.openshm.patcher.ui.nav.skippableSteps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The dialog over the welcome screen while the steps that need nothing are worked out. */
sealed interface SkipDialog {
    /** The remembered watch is being asked for its version. */
    data object Checking : SkipDialog

    /** These steps are passed over; the dialog stays until the user confirms. */
    data class Skipping(val steps: List<SkippedStep>) : SkipDialog
}

/**
 * Runs when the user starts the setup: asks the remembered watch (if any) and the phone, works out
 * which steps are already done, shows them until [confirm] and then [go]es on. [apply] records the
 * decision just before moving on, so a cancelled dialog skips nothing.
 */
internal class SkipPreflight(
    private val scope: CoroutineScope,
    private val watchRemembered: () -> Boolean,
    private val detect: suspend () -> DetectedWatch?,
    private val wearableReady: suspend () -> Boolean,
    private val apply: (steps: List<SkippedStep>, watch: DetectedWatch?) -> Unit,
) {
    private val _dialog = MutableStateFlow<SkipDialog?>(null)
    val dialog: StateFlow<SkipDialog?> = _dialog.asStateFlow()
    private val _go = Channel<Unit>(Channel.CONFLATED)

    /** Fires once per [start] that was not cancelled: open the next step that is not skipped. */
    val go: Flow<Unit> = _go.receiveAsFlow()
    private var job: Job? = null
    private var pendingWatch: DetectedWatch? = null

    fun start() {
        if (job?.isActive == true || _dialog.value != null) return
        job = scope.launch {
            val watch = if (watchRemembered()) {
                _dialog.value = SkipDialog.Checking
                detect()
            } else {
                null
            }
            val steps = skippableSteps(watch, wearableReady())
            if (steps.isEmpty()) {
                finish(steps, watch)
            } else {
                pendingWatch = watch
                _dialog.value = SkipDialog.Skipping(steps)
            }
        }
    }

    /** "Got it" on the dialog: skip the listed steps and move on. */
    fun confirm() {
        val shown = _dialog.value as? SkipDialog.Skipping ?: return
        finish(shown.steps, pendingWatch)
    }

    /** "Set up manually" on the dialog: skip nothing and walk every step, keeping what was detected. */
    fun manual() {
        if (_dialog.value !is SkipDialog.Skipping) return
        finish(emptyList(), pendingWatch)
    }

    private fun finish(steps: List<SkippedStep>, watch: DetectedWatch?) {
        apply(steps, watch)
        _go.trySend(Unit)
        _dialog.value = null
        pendingWatch = null
    }

    /** Back on the dialog: stay on the welcome screen and skip nothing. */
    fun cancel() {
        job?.cancel()
        job = null
        pendingWatch = null
        _dialog.value = null
    }
}
