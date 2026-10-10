package io.openshm.patcher.ui.screens.profile

import androidx.lifecycle.ViewModel
import io.openshm.patcher.data.SetupSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProfileViewModel : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState(selected = SetupSession.state.value.watchProfile))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    fun onEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.Select -> {
                SetupSession.update { it.copy(watchProfile = event.profile) }
                _state.value = _state.value.copy(selected = event.profile)
            }
        }
    }
}
