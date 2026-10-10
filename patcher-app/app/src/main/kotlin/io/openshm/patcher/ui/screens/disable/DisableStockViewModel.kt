package io.openshm.patcher.ui.screens.disable

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.device.AdbWatchDevice
import io.openshm.patcher.device.WatchDevice
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.ui.screens.install.watchErrorRes
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun DisableStockUiState.reduce(event: DisableStockEvent): DisableStockUiState = when (event) {
    is DisableStockEvent.Toggle -> copy(
        choices = choices.map { if (it.app.packageName == event.packageName) it.copy(selected = !it.selected) else it },
    )
    DisableStockEvent.Disable -> copy(running = true, errorRes = null)
}

private fun DisableStockUiState.setOutcome(packageName: String, outcome: StockOutcome) = copy(
    choices = choices.map { if (it.app.packageName == packageName) it.copy(outcome = outcome) else it },
)

/**
 * Turns the selected stock apps off (`pm disable-user`) when they are installed; an app that is not
 * there is marked and skipped. Nothing is uninstalled. Sets [DisableStockUiState.done] only when
 * every selected app ended up off or absent.
 */
internal suspend fun disableSelected(device: WatchDevice, state: MutableStateFlow<DisableStockUiState>) {
    state.update { it.copy(running = true, done = false, errorRes = null) }
    var failures = 0
    try {
        for (choice in state.value.choices.filter { it.selected }) {
            val pkg = choice.app.packageName
            state.update { it.setOutcome(pkg, StockOutcome.Running) }
            try {
                if (device.isInstalled(pkg)) {
                    device.disable(pkg)
                    state.update { it.setOutcome(pkg, StockOutcome.Disabled) }
                } else {
                    state.update { it.setOutcome(pkg, StockOutcome.NotInstalled) }
                }
            } catch (e: AdbException.CommandFailed) {
                failures++
                state.update { it.setOutcome(pkg, StockOutcome.Failed) }
            } catch (e: IOException) {
                state.update { it.setOutcome(pkg, StockOutcome.None).copy(errorRes = watchErrorRes(e)) }
                return
            }
        }
        state.update { if (failures == 0) it.copy(done = true) else it.copy(errorRes = R.string.disable_error_some) }
    } finally {
        state.update { it.copy(running = false) }
    }
}

/** The apps to turn off come straight from the catalogue; required apps start selected, optional ones start off. */
class DisableStockViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(
        DisableStockUiState(
            choices = AppCatalogue.current.stockAppsToDisable
                .filter { it.device == Device.Watch }
                .map { StockAppChoice(it, selected = !it.optional) },
        ),
    )
    val state: StateFlow<DisableStockUiState> = _state.asStateFlow()

    private val device = AdbWatchDevice(WatchLink.get(application))

    fun onEvent(event: DisableStockEvent) {
        val s = _state.value
        if (event is DisableStockEvent.Disable && (s.running || s.selectedCount == 0)) return
        _state.update { it.reduce(event) }
        if (event is DisableStockEvent.Disable) viewModelScope.launch(Dispatchers.IO) { disableSelected(device, _state) }
    }

    /** The screen moved on. */
    fun onNavigated() = _state.update { it.copy(done = false) }
}
