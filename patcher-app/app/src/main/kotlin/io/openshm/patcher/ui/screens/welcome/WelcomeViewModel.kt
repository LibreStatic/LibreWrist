package io.openshm.patcher.ui.screens.welcome

import androidx.lifecycle.ViewModel
import io.openshm.patcher.data.BundledCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.data.WatchProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the build installs, grants and turns off on each device, from the catalogue and the chosen prefix. */
internal fun buildChanges(prefix: String, profile: WatchProfile): List<DeviceChanges> {
    val outputs = BundledCatalogue.outputs.filter { it.id in profile.outputIds }
    return Device.entries.map { device ->
        val onDevice = outputs.filter { it.device == device }
        DeviceChanges(
            device = device,
            installs = onDevice.map { ChangeLine(it.label, it.packageName(prefix)) },
            grants = onDevice.flatMap { out ->
                BundledCatalogue.grants[out.id].orEmpty().map { ChangeLine(it.name, out.packageName(prefix)) }
            },
            disables = BundledCatalogue.stockAppsToDisable
                .filter { it.device == device }
                .map { ChangeLine(it.label, it.packageName) },
        )
    }
}

class WelcomeViewModel : ViewModel() {
    private val _state = MutableStateFlow(
        SetupSession.state.value.let { WelcomeUiState(changes = buildChanges(it.packagePrefix, it.watchProfile)) },
    )
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    fun onEvent(event: WelcomeEvent) {
        when (event) {
            WelcomeEvent.ShowChanges -> _state.update { it.copy(changesSheetVisible = true) }
            WelcomeEvent.HideChanges -> _state.update { it.copy(changesSheetVisible = false) }
        }
    }
}
