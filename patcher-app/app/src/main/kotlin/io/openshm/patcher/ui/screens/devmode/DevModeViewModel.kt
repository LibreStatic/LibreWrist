package io.openshm.patcher.ui.screens.devmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shell-stage view model: the developer-mode probe over ADB replaces the timed check. */
class DevModeViewModel : ViewModel() {
    private val _state = MutableStateFlow(DevModeUiState())
    val state: StateFlow<DevModeUiState> = _state.asStateFlow()

    fun onEvent(event: DevModeEvent) {
        when (event) {
            DevModeEvent.Check -> viewModelScope.launch {
                _state.update { it.copy(checking = true) }
                delay(CHECK_DELAY_MS)
                _state.update { it.copy(checking = false, detected = true) }
            }
            DevModeEvent.OpenHealthPlatformSettings -> Unit
        }
    }

    private companion object {
        const val CHECK_DELAY_MS = 800L
    }
}
