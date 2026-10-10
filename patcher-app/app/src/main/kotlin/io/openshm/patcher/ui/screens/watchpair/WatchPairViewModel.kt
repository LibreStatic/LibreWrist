package io.openshm.patcher.ui.screens.watchpair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pure reducer so it can be unit tested without a ViewModel. */
internal fun WatchPairUiState.reduce(event: WatchPairEvent): WatchPairUiState = when (event) {
    is WatchPairEvent.CodeChanged -> copy(code = event.code.filter(Char::isDigit).take(PAIRING_CODE_LENGTH), errorRes = null)
    is WatchPairEvent.AddressChanged -> copy(foundAddress = event.address.ifBlank { null })
    WatchPairEvent.ToggleEditAddress -> copy(editingAddress = !editingAddress)
    WatchPairEvent.Pair -> if (canPair) copy(pairing = true) else this
}

/**
 * Shell-stage view model. Discovery and pairing arrive with the ADB layer; until then no watch is
 * found on its own, so no invented address or code is ever shown, and a manual address plus a
 * complete code is accepted after a short delay.
 */
class WatchPairViewModel : ViewModel() {
    private val _state = MutableStateFlow(WatchPairUiState())
    val state: StateFlow<WatchPairUiState> = _state.asStateFlow()

    fun onEvent(event: WatchPairEvent) {
        val wasPairing = _state.value.pairing
        _state.update { it.reduce(event) }
        if (!wasPairing && _state.value.pairing) {
            viewModelScope.launch {
                delay(PAIR_DELAY_MS)
                _state.update { it.copy(pairing = false, paired = true) }
            }
        }
    }

    private companion object {
        const val PAIR_DELAY_MS = 1200L
    }
}
