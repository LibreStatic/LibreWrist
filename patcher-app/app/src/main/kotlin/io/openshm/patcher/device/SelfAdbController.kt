package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.toPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Who started the attempt, so the one that owns the connection is the only one that takes it. */
enum class SelfAdbPurpose { PhoneGrants, Troubleshoot, PhoneLog, Setup }

/**
 * What the screens see of the phone's Wireless debugging fallback. [client] is set once connected
 * and until the grants are done; [error] and [needsNotifications] explain a [SelfAdbPhase.Failed].
 */
data class SelfAdbSnapshot(
    val purpose: SelfAdbPurpose? = null,
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

    /**
     * A code that arrived before the session was ready for it: the guide reads it from the pairing
     * dialog the moment it opens, while the session still needs a moment to discover the pairing service.
     */
    @Volatile
    private var pendingCode: String? = null

    /** The session's latest state is [SelfAdbState.WaitingForCode]: a code goes straight in. */
    @Volatile
    private var waitingForCode = false

    /** Starts an attempt for [purpose]; false (nothing changes) when one is already under way. */
    fun begin(purpose: SelfAdbPurpose): Boolean {
        while (true) {
            val current = _state.value
            if (current.active) return false
            if (_state.compareAndSet(current, SelfAdbSnapshot(purpose, SelfAdbPhase.Reconnecting))) {
                pendingCode = null
                return true
            }
        }
    }

    /** Follows the session. [SelfAdbState.Idle] (before start, after stop) is not progress and is ignored. */
    fun onSession(session: SelfAdbState) {
        when (session) {
            // Handed over once: a failed pairing waits for a new code, never the same one again.
            is SelfAdbState.WaitingForCode -> pendingCode?.let { code ->
                pendingCode = null
                codeSink?.invoke(code)
            }
            else -> Unit
        }
        waitingForCode = session is SelfAdbState.WaitingForCode
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
     * Hands out the connection once, to the owner of the attempt. Null when not connected, when
     * the attempt belongs to another [purpose] or when it was already taken.
     */
    fun takeClient(purpose: SelfAdbPurpose): AdbClient? {
        while (true) {
            val current = _state.value
            val client = current.client
            if (!current.active || current.purpose != purpose || client == null || current.granting) return null
            if (_state.compareAndSet(current, current.copy(granting = true))) return client
        }
    }

    fun fail(message: String?, needsNotifications: Boolean = false) {
        pendingCode = null
        _state.update { SelfAdbSnapshot(it.purpose, SelfAdbPhase.Failed, error = message, needsNotifications = needsNotifications) }
    }

    /** The attempt is over (grants done, or the user cancelled): back to idle; the service stops. */
    fun finish() {
        pendingCode = null
        _state.value = SelfAdbSnapshot()
    }

    /** [finish], but only for an attempt of [purpose] that is still under way (a view model going away). */
    fun finishIfOwned(purpose: SelfAdbPurpose) {
        _state.update { if (it.active && it.purpose == purpose) SelfAdbSnapshot() else it }
    }

    fun submitCode(code: String) {
        if (waitingForCode) codeSink?.invoke(code) else pendingCode = code
    }

    internal fun setCodeSink(sink: ((String) -> Unit)?) {
        codeSink = sink
    }
}
