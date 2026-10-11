package io.openshm.patcher.ui.screens.wearable

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.ShellResult
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbPurpose
import io.openshm.patcher.device.SelfAdbSnapshot
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val GMS_PACKAGE = "com.google.android.gms"
internal const val CLEAR_GMS_COMMAND = "pm clear $GMS_PACKAGE"

/** How the "clear Google Play services data" run is going; null in the state means nothing was asked yet. */
enum class ClearOutcome { Running, Success, Failed, PairedOnly }

data class TroubleshootUiState(
    /** The phase of this screen's own Wireless debugging attempt; null when it has none under way. */
    val adb: SelfAdbPhase? = null,
    /** The warning dialog is showing; nothing is cleared until it is confirmed. */
    val confirming: Boolean = false,
    /** Confirmed, and waiting for the connection to this phone. */
    val pendingClear: Boolean = false,
    val outcome: ClearOutcome? = null,
    @StringRes val errorRes: Int? = null,
    /** Extra detail for a failure (the shell output or the exception message); shown as is. */
    val detail: String? = null,
)

sealed interface TroubleshootEvent {
    data object OpenPlayServicesSettings : TroubleshootEvent
    data object PairAdb : TroubleshootEvent
    data class SubmitPairingCode(val code: String) : TroubleshootEvent
    data object CancelAdb : TroubleshootEvent

    /** The clear button: only ever opens the warning dialog. */
    data object ClearClicked : TroubleshootEvent
    data object ClearDismissed : TroubleshootEvent
    data object ClearConfirmed : TroubleshootEvent
}

/** `pm clear` prints "Success" or "Failed"; only the first counts, whatever the exit code says. */
internal fun clearSucceeded(result: ShellResult): Boolean = "Success" in result.stdout || "Success" in result.stderr

internal fun clearFailureDetail(result: ShellResult): String =
    (result.stdout + "\n" + result.stderr).trim().ifEmpty { "exit code ${result.exitCode}" }

/**
 * The troubleshooting logic without Android: the warning dialog that always comes first, the
 * wait for the connection after it, the single `pm clear` run and the end of the attempt.
 * [startAdb] starts the service and opens the Wireless debugging settings once an attempt was
 * accepted; [runClear] sends the command on the connection. The view model wraps it.
 */
internal class TroubleshootModel(
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val startAdb: () -> Unit,
    private val runClear: suspend (AdbClient) -> ShellResult,
) {
    private val _state = MutableStateFlow(TroubleshootUiState())
    val state: StateFlow<TroubleshootUiState> = _state.asStateFlow()

    fun onEvent(event: TroubleshootEvent) {
        when (event) {
            TroubleshootEvent.PairAdb -> pair()
            is TroubleshootEvent.SubmitPairingCode -> SelfAdbController.submitCode(event.code)
            TroubleshootEvent.CancelAdb -> {
                SelfAdbController.finishIfOwned(PURPOSE)
                _state.update { it.copy(pendingClear = false, adb = null) }
            }
            TroubleshootEvent.ClearClicked ->
                if (_state.value.outcome != ClearOutcome.Running) _state.update { it.copy(confirming = true) }
            TroubleshootEvent.ClearDismissed -> _state.update { it.copy(confirming = false) }
            TroubleshootEvent.ClearConfirmed -> confirmed()
            TroubleshootEvent.OpenPlayServicesSettings -> Unit // an Android intent: the view model sends it
        }
    }

    /** Pairs only: the connection is dropped as soon as it is made (adbd remembers the pairing). */
    private fun pair() {
        _state.update { it.copy(pendingClear = false) }
        beginAttempt()
    }

    private fun confirmed() {
        if (!_state.value.confirming) return // the dialog must have been shown
        _state.update { it.copy(confirming = false, pendingClear = true, outcome = null, errorRes = null, detail = null) }
        if (beginAttempt()) onAdb(SelfAdbController.state.value) // it may be connected already
    }

    /** True when an attempt of this screen is under way afterwards. */
    private fun beginAttempt(): Boolean {
        val current = SelfAdbController.state.value
        if (current.active && current.purpose == PURPOSE) return true
        if (!SelfAdbController.begin(PURPOSE)) {
            _state.update { it.copy(pendingClear = false, errorRes = R.string.troubleshoot_adb_busy, detail = null) }
            return false
        }
        _state.update { it.copy(outcome = null, errorRes = null, detail = null) }
        startAdb()
        return true
    }

    /** Follows [SelfAdbController]; snapshots of another screen's attempt are not ours. */
    fun onAdb(snapshot: SelfAdbSnapshot) {
        if (snapshot.purpose != PURPOSE) {
            _state.update { it.copy(adb = null) }
            return
        }
        _state.update { it.copy(adb = snapshot.phase) }
        when (snapshot.phase) {
            SelfAdbPhase.Failed -> _state.update {
                it.copy(
                    pendingClear = false,
                    errorRes = if (snapshot.needsNotifications) R.string.install_error_notifications else R.string.phone_adb_failed,
                    detail = snapshot.error,
                )
            }
            SelfAdbPhase.Granting -> {
                val client = SelfAdbController.takeClient(PURPOSE) ?: return
                if (_state.value.pendingClear) clear(client) else pairedOnly()
            }
            else -> Unit
        }
    }

    private fun pairedOnly() {
        _state.update { it.copy(outcome = ClearOutcome.PairedOnly, adb = null) }
        SelfAdbController.finish()
    }

    private fun clear(client: AdbClient) {
        _state.update { it.copy(pendingClear = false, outcome = ClearOutcome.Running, adb = null) }
        scope.launch(io) {
            try {
                val result = runClear(client)
                _state.update {
                    if (clearSucceeded(result)) {
                        it.copy(outcome = ClearOutcome.Success)
                    } else {
                        it.copy(outcome = ClearOutcome.Failed, errorRes = R.string.troubleshoot_clear_failed, detail = clearFailureDetail(result))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(outcome = ClearOutcome.Failed, errorRes = R.string.troubleshoot_clear_failed, detail = e.message ?: e.javaClass.simpleName)
                }
            } finally {
                SelfAdbController.finish()
            }
        }
    }

    /** The screen is gone: drop an attempt that is still waiting on this screen. */
    fun onCleared() = SelfAdbController.finishIfOwned(PURPOSE)

    companion object {
        val PURPOSE = SelfAdbPurpose.Troubleshoot
    }
}
