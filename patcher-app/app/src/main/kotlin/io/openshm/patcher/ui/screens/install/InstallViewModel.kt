package io.openshm.patcher.ui.screens.install

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.AdbWatchDevice
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.Dispatchers
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
    return AppCatalogue.current.outputs
        .filter { it.device == device && it.id in session.watchProfile.outputIds }
        .map { out ->
            InstallItem(
                outputId = out.id,
                label = out.label,
                packageName = out.packageName(session.packagePrefix),
                status = CheckStatus.Pending,
                grants = AppCatalogue.current.grants[out.id].orEmpty().map { GrantRow(it, CheckStatus.Pending) },
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
 * Shell-stage view model for the phone install screen: walks the list on a timer. Installs through
 * the platform installer replace [run].
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

/** Installs the built watch outputs over ADB and applies their grants; the work is in [WatchInstallRunner]. */
class InstallWatchViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(InstallUiState(installItemsFor(Device.Watch)))
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    private val runner = WatchInstallRunner(
        device = AdbWatchDevice(WatchLink.get(application)),
        build = { BuildStorage(application).record.read() },
        prefix = { SetupSession.state.value.packagePrefix },
        lockPrefix = { SetupSession.update { it.copy(prefixLocked = true) } },
    )

    fun onEvent(event: InstallEvent) {
        when (event) {
            InstallEvent.Start -> start()
            is InstallEvent.OpenGrantSettings -> Unit
        }
    }

    private fun start() {
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        viewModelScope.launch(Dispatchers.IO) { runner.run(_state) }
    }
}
