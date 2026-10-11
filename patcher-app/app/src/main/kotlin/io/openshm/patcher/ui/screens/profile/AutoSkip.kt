package io.openshm.patcher.ui.screens.profile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** How long the "skipping this step" notice stays before the flow moves on. */
const val AUTO_SKIP_HOLD_MILLIS = 3_000L

/**
 * Skips the profile step once, when the watch reports its version before the user touched anything:
 * [skipping] shows the notice for [holdMillis], then [go] fires. Coming back to the step never skips again.
 */
internal class AutoSkip(private val scope: CoroutineScope, private val holdMillis: Long = AUTO_SKIP_HOLD_MILLIS) {
    private var armed = true
    private var gone = false
    private val _skipping = MutableStateFlow(false)
    val skipping: StateFlow<Boolean> = _skipping.asStateFlow()
    private val _go = Channel<Unit>(Channel.CONFLATED)
    val go: Flow<Unit> = _go.receiveAsFlow()

    /** The user chose, asked or continued by hand: the step is theirs now. */
    fun disarm() {
        armed = false
    }

    fun onDetected() {
        if (!armed) return
        armed = false
        _skipping.value = true
        scope.launch {
            delay(holdMillis)
            gone = true
            _go.trySend(Unit)
        }
    }

    /** The step is on screen again (after Back): let the user change the answer. */
    fun onShown() {
        if (gone) _skipping.value = false
    }
}
