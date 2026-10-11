package io.openshm.patcher.ui.screens.welcome

import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.ui.nav.SkippedStep
import io.openshm.patcher.ui.nav.skippableSteps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** How long the "skipping these steps" dialog stays before the flow moves on. */
const val SKIP_HOLD_MILLIS = 3_000L

/** The dialog over the welcome screen while the steps that need nothing are worked out. */
sealed interface SkipDialog {
    /** The remembered watch is being asked for its version. */
    data object Checking : SkipDialog

    /** These steps are passed over; the dialog stays [SKIP_HOLD_MILLIS] so they can be read. */
    data class Skipping(val steps: List<SkippedStep>) : SkipDialog
}

/**
 * Runs when the user starts the setup: asks the remembered watch (if any) and the phone, works out
 * which steps are already done, shows them and then [go]es on. [apply] records the decision just
 * before moving on, so a cancelled dialog skips nothing.
 */
internal class SkipPreflight(
    private val scope: CoroutineScope,
    private val watchRemembered: () -> Boolean,
    private val detect: suspend () -> DetectedWatch?,
    private val wearableReady: suspend () -> Boolean,
    private val apply: (steps: List<SkippedStep>, watch: DetectedWatch?) -> Unit,
    private val holdMillis: Long = SKIP_HOLD_MILLIS,
) {
    private val _dialog = MutableStateFlow<SkipDialog?>(null)
    val dialog: StateFlow<SkipDialog?> = _dialog.asStateFlow()
    private val _go = Channel<Unit>(Channel.CONFLATED)

    /** Fires once per [start] that was not cancelled: open the next step that is not skipped. */
    val go: Flow<Unit> = _go.receiveAsFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val watch = if (watchRemembered()) {
                _dialog.value = SkipDialog.Checking
                detect()
            } else {
                null
            }
            val steps = skippableSteps(watch, wearableReady())
            if (steps.isNotEmpty()) {
                _dialog.value = SkipDialog.Skipping(steps)
                delay(holdMillis)
            }
            apply(steps, watch)
            _go.trySend(Unit)
            _dialog.value = null
        }
    }

    /** Back on the dialog: stay on the welcome screen and skip nothing. */
    fun cancel() {
        job?.cancel()
        job = null
        _dialog.value = null
    }
}
