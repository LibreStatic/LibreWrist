package io.openshm.patcher.ui.screens.install

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.BundledCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The install list for one device, in the catalogue `installOrder` (shm-watch, bridge-watch, alarm
 * fork on the watch), each with the grants the catalogue lists for it.
 */
internal fun installItemsFor(device: Device): List<InstallItem> {
    val session = SetupSession.state.value
    return BundledCatalogue.outputs
        .filter { it.device == device && it.id in session.watchProfile.outputIds }
        .map { out ->
            InstallItem(
                outputId = out.id,
                label = out.label,
                packageName = out.packageName(session.packagePrefix),
                status = CheckStatus.Pending,
                grants = BundledCatalogue.grants[out.id].orEmpty().map { GrantRow(it, CheckStatus.Pending) },
            )
        }
}

internal fun InstallUiState.setItem(index: Int, status: CheckStatus, grantStatus: CheckStatus? = null) = copy(
    items = items.mapIndexed { i, item ->
        if (i != index) item
        else item.copy(
            status = status,
            grants = if (grantStatus == null) item.grants else item.grants.map { it.copy(status = grantStatus) },
        )
    },
)

/**
 * Shell-stage view model shared by the phone and watch install screens: walks the list on a timer.
 * Package installs and grants through the platform installer and ADB replace [run].
 */
open class InstallViewModel(device: Device) : ViewModel() {
    private val _state = MutableStateFlow(InstallUiState(installItemsFor(device)))
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    fun onEvent(event: InstallEvent) {
        when (event) {
            InstallEvent.Start -> run()
            is InstallEvent.OpenGrantSettings -> Unit
        }
    }

    private fun run() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, errorRes = null) }
            for (index in _state.value.items.indices) {
                _state.update { it.setItem(index, CheckStatus.Running, CheckStatus.Pending) }
                delay(STEP_DELAY_MS)
                _state.update { it.setItem(index, CheckStatus.Passed, CheckStatus.Passed) }
            }
            _state.update { it.copy(running = false) }
        }
    }

    private companion object {
        const val STEP_DELAY_MS = 600L
    }
}

class InstallPhoneViewModel : InstallViewModel(Device.Phone)

class InstallWatchViewModel : InstallViewModel(Device.Watch)
