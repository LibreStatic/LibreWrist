package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.toPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the screens see of the phone's Wireless debugging fallback. [client] is set once connected
 * and until the grants are done; [error] and [needsNotifications] explain a [SelfAdbPhase.Failed].
 */
data class SelfAdbSnapshot(
    val phase: SelfAdbPhase? = null,
    val client: AdbClient? = null,
    val granting: Boolean = false,
    val error: String? = null,
    val needsNotifications: Boolean = false,
) {
    /** The attempt is under way, so [SelfAdbService] has to keep running. */
    val active: Boolean get() = phase != null && phase != SelfAdbPhase.Failed
}

/**
 * The process-wide source of truth for the fallback, like `BuildController`: [SelfAdbService]
 * writes the session's progress here and a view model reads it, so recreating the view model (or
 * the screen) changes nothing. The service stops once the attempt is no longer [SelfAdbSnapshot.active],
 * which is what [finish], [fail] and a timeout cause.
 */
object SelfAdbController {
    private val _state = MutableStateFlow(SelfAdbSnapshot())
    val state: StateFlow<SelfAdbSnapshot> = _state.asStateFlow()

    @Volatile
    private var codeSink: ((String) -> Unit)? = null

    /** Starts an attempt; false (nothing changes) when one is already under way. */
    fun begin(): Boolean {
        while (true) {
            val current = _state.value
            if (current.active) return false
            if (_state.compareAndSet(current, SelfAdbSnapshot(phase = SelfAdbPhase.WaitingForService))) return true
        }
    }

    /** Follows the session. [SelfAdbState.Idle] (before start, after stop) is not progress and is ignored. */
    fun onSession(session: SelfAdbState) {
        val phase = session.toPhase() ?: return
        _state.update { current ->
            if (!current.active) return@update current // stopped or failed already: late states do not revive it
            when (session) {
                is SelfAdbState.Connected -> current.copy(phase = phase, client = session.client)
                is SelfAdbState.Failed -> current.copy(phase = phase, client = null, error = session.error.message)
                else -> current.copy(phase = phase, client = null)
            }
        }
    }

    /**
     * Hands out the connection once, to whoever applies the grants. Null when not connected or
     * when another caller already took it.
     */
    fun takeClient(): AdbClient? {
        while (true) {
            val current = _state.value
            val client = current.client
            if (!current.active || client == null || current.granting) return null
            if (_state.compareAndSet(current, current.copy(granting = true))) return client
        }
    }

    fun fail(message: String?, needsNotifications: Boolean = false) {
        _state.value = SelfAdbSnapshot(SelfAdbPhase.Failed, error = message, needsNotifications = needsNotifications)
    }

    /** The attempt is over (grants done, or the user cancelled): back to idle; the service stops. */
    fun finish() {
        _state.value = SelfAdbSnapshot()
    }

    fun submitCode(code: String) {
        codeSink?.invoke(code)
    }

    internal fun setCodeSink(sink: ((String) -> Unit)?) {
        codeSink = sink
    }
}
