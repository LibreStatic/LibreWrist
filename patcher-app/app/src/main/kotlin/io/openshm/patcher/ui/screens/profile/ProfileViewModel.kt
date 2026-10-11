package io.openshm.patcher.ui.screens.profile

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.WatchDetection
import io.openshm.patcher.device.WatchLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ProfileUiState(selected = SetupSession.state.value.watchProfile))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val autoSkip = AutoSkip(viewModelScope)

    /** Fires once when the watch answered on its own and the notice was shown: move to the next step. */
    val autoContinue: Flow<Unit> = autoSkip.go

    init {
        // A remembered watch answers authoritatively; any failure is silent (the user just picks).
        WatchDetection.start(WatchLink.get(application))
        viewModelScope.launch {
            WatchDetection.state.collect { detection ->
                val detected = detection.watch ?: return@collect
                if (_state.value.detected != null) return@collect
                select(detected.profile)
                _state.update { it.copy(detected = detected) }
                autoSkip.onDetected()
            }
        }
        viewModelScope.launch { autoSkip.skipping.collect { s -> _state.update { it.copy(skipping = s) } } }
    }

    /** The step is on screen (first time or after Back). */
    fun onShown() = autoSkip.onShown()

    /** The user pressed Continue themselves. */
    fun onContinue() = autoSkip.disarm()

    fun onEvent(event: ProfileEvent) {
        autoSkip.disarm()
        when (event) {
            is ProfileEvent.Select -> select(event.profile)
            // The host asks for the Bluetooth permission and reports back through onBluetoothPermission.
            ProfileEvent.CheckPairedWatches -> Unit
        }
    }

    /** The result of the Bluetooth permission request (or its existing grant). */
    fun onBluetoothPermission(granted: Boolean) {
        autoSkip.disarm()
        if (!granted) {
            _state.update { it.copy(pairedCheck = PairedCheck.Denied, hint = null) }
            return
        }
        val names = bondedNames()
        val hint = WatchModelHint.best(names)
        _state.update { it.copy(pairedCheck = if (hint == null) PairedCheck.NoWatch else PairedCheck.Done, hint = hint) }
        // The watch itself is authoritative: a hint never overrides what it reported.
        if (hint is WatchModelHint.Certain && _state.value.detected == null) select(WatchProfile.WearOs5)
    }

    private fun select(profile: WatchProfile) {
        SetupSession.update { it.copy(watchProfile = profile) }
        _state.update { it.copy(selected = profile) }
    }

    private fun bondedNames(): List<String> = try {
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.bondedDevices.orEmpty().mapNotNull { it.name }
    } catch (e: SecurityException) {
        emptyList()
    }
}
