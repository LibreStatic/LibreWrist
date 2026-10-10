package io.openshm.patcher.ui.screens.acquire

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.BundledCatalogue
import io.openshm.patcher.data.SetupSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun AcquireUiState.updateCard(inputId: String, change: (AcquireCard) -> AcquireCard) =
    copy(cards = cards.map { if (it.input.id == inputId) change(it) else it })

internal fun AcquireUiState.reduce(event: AcquireEvent): AcquireUiState = when (event) {
    is AcquireEvent.ToggleMoreWays -> updateCard(event.inputId) { it.copy(moreWaysExpanded = !it.moreWaysExpanded) }
    is AcquireEvent.UseFile -> updateCard(event.inputId) { it.copy(moreWaysExpanded = false) }
    is AcquireEvent.PullFromDevice -> updateCard(event.inputId) { it.copy(moreWaysExpanded = false) }
    is AcquireEvent.OpenMirror -> this
}

/**
 * Shell-stage view model. Choosing a file or pulling from a device runs a simulated transfer so the
 * flow can be walked end to end; the real file picker, device pull and SHA-256 check replace
 * [simulateTransfer] when the platform layer lands (the screen state does not change).
 */
class AcquireViewModel : ViewModel() {
    private val _state = MutableStateFlow(
        AcquireUiState(
            SetupSession.state.value.watchProfile.inputIds.map { AcquireCard(BundledCatalogue.input(it)) },
        ),
    )
    val state: StateFlow<AcquireUiState> = _state.asStateFlow()

    fun onEvent(event: AcquireEvent) {
        _state.update { it.reduce(event) }
        when (event) {
            is AcquireEvent.UseFile -> simulateTransfer(event.inputId)
            is AcquireEvent.PullFromDevice -> simulateTransfer(event.inputId)
            else -> Unit
        }
    }

    private fun simulateTransfer(inputId: String) {
        viewModelScope.launch {
            for (step in 0..STEPS) {
                _state.update { s ->
                    s.updateCard(inputId) {
                        it.copy(progress = AcquireProgress.Downloading(step * STUB_TOTAL_BYTES / STEPS, STUB_TOTAL_BYTES))
                    }
                }
                delay(STEP_DELAY_MS)
            }
            _state.update { s -> s.updateCard(inputId) { it.copy(progress = AcquireProgress.Verified(STUB_TOTAL_BYTES)) } }
        }
    }

    private companion object {
        const val STEPS = 10
        const val STEP_DELAY_MS = 150L
        const val STUB_TOTAL_BYTES = 64L * 1024 * 1024
    }
}
