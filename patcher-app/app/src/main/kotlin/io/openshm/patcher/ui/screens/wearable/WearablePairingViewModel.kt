package io.openshm.patcher.ui.screens.wearable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun initialWearableChecks(status: CheckStatus) = listOf(
    WearableCheck("wearable-installed", R.string.wearable_check_app, R.string.wearable_check_app_detail, status),
    WearableCheck("watch-paired", R.string.wearable_check_paired, R.string.wearable_check_paired_detail, status),
    WearableCheck("data-layer", R.string.wearable_check_datalayer, R.string.wearable_check_datalayer_detail, status),
)

/**
 * Shell-stage view model: the checks resolve after a short delay. The Wearable pairing detection
 * (companion association and Data Layer node) lands with the platform layer; the screen and its
 * state do not change when it does.
 */
class WearablePairingViewModel : ViewModel() {
    private val _state = MutableStateFlow(WearablePairingUiState(initialWearableChecks(CheckStatus.Pending)))
    val state: StateFlow<WearablePairingUiState> = _state.asStateFlow()

    init {
        runChecks()
    }

    fun onEvent(event: WearablePairingEvent) {
        when (event) {
            WearablePairingEvent.Recheck -> runChecks()
        }
    }

    private fun runChecks() {
        viewModelScope.launch {
            _state.update { WearablePairingUiState(initialWearableChecks(CheckStatus.Running)) }
            delay(CHECK_DELAY_MS)
            _state.update { WearablePairingUiState(initialWearableChecks(CheckStatus.Passed)) }
        }
    }

    private companion object {
        const val CHECK_DELAY_MS = 900L
    }
}
