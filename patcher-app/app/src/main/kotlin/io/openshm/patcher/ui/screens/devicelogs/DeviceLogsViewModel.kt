package io.openshm.patcher.ui.screens.devicelogs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.device.DeviceLogs
import io.openshm.patcher.device.DeviceLogsState
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.LogDevice
import io.openshm.patcher.device.PhoneSettings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A short result shown in a snackbar. */
enum class DeviceLogsMessage { Exported, ExportFailed }

data class DeviceLogsUiState(
    val logs: DeviceLogsState = DeviceLogsState(),
    /** The device whose tab is selected. */
    val tab: LogDevice = LogDevice.Watch,
    /** The bottom sheet that explains a disconnected device. */
    val helpOpen: Boolean = false,
    val message: DeviceLogsMessage? = null,
)

sealed interface DeviceLogsEvent {
    data class SelectTab(val device: LogDevice) : DeviceLogsEvent
    data object ShowHelp : DeviceLogsEvent
    data object HideHelp : DeviceLogsEvent
    data object Retry : DeviceLogsEvent
    data object PairPhone : DeviceLogsEvent
    data object OpenDeveloperOptions : DeviceLogsEvent

    /** Handled by the nav host: it owns the document picker and the navigation. */
    data object OpenWatchPair : DeviceLogsEvent
    data object Export : DeviceLogsEvent
    data object ToggleCapture : DeviceLogsEvent
    data object Clear : DeviceLogsEvent
    data object DismissMessage : DeviceLogsEvent
}

class DeviceLogsViewModel(application: Application) : AndroidViewModel(application) {
    private data class Local(val tab: LogDevice = LogDevice.Watch, val helpOpen: Boolean = false, val message: DeviceLogsMessage? = null)

    private val local = MutableStateFlow(Local())
    private val outgoing = Channel<LaunchRequest>(Channel.BUFFERED)

    /** System screens to open (Developer options). */
    val launches: Flow<LaunchRequest> = outgoing.receiveAsFlow()

    val state: StateFlow<DeviceLogsUiState> = combine(DeviceLogs.state, local) { logs, l ->
        DeviceLogsUiState(logs, l.tab, l.helpOpen, l.message)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DeviceLogsUiState(DeviceLogs.state.value))

    init {
        // Opening the screen records; Stop is an explicit choice made on this screen or in the notification.
        if (!DeviceLogs.state.value.capturing) DeviceLogs.start(application)
    }

    fun onEvent(event: DeviceLogsEvent) {
        val app = getApplication<Application>()
        when (event) {
            is DeviceLogsEvent.SelectTab -> local.update { it.copy(tab = event.device, helpOpen = false) }
            DeviceLogsEvent.ShowHelp -> local.update { it.copy(helpOpen = true) }
            DeviceLogsEvent.HideHelp -> local.update { it.copy(helpOpen = false) }
            DeviceLogsEvent.Retry -> {
                DeviceLogs.retry(local.value.tab)
                local.update { it.copy(helpOpen = false) }
            }
            DeviceLogsEvent.PairPhone -> {
                DeviceLogs.pairPhone(app)
                local.update { it.copy(helpOpen = false) }
            }
            DeviceLogsEvent.OpenDeveloperOptions -> {
                outgoing.trySend(PhoneSettings.wirelessDebugging())
                local.update { it.copy(helpOpen = false) }
            }
            DeviceLogsEvent.ToggleCapture ->
                if (DeviceLogs.state.value.capturing) DeviceLogs.stop(app) else DeviceLogs.start(app)
            DeviceLogsEvent.Clear -> DeviceLogs.clear()
            DeviceLogsEvent.DismissMessage -> local.update { it.copy(message = null) }
            DeviceLogsEvent.OpenWatchPair -> local.update { it.copy(helpOpen = false) }
            DeviceLogsEvent.Export -> Unit
        }
    }

    /** The document picker answered: [uri] is null when it was cancelled. */
    fun export(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = runCatching { DeviceLogs.exportTo(getApplication(), uri) }.isSuccess
            local.update { it.copy(message = if (ok) DeviceLogsMessage.Exported else DeviceLogsMessage.ExportFailed) }
        }
    }
}
