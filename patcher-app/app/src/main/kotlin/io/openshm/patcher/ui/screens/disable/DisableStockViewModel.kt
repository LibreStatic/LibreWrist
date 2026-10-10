package io.openshm.patcher.ui.screens.disable

import androidx.lifecycle.ViewModel
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal fun DisableStockUiState.reduce(event: DisableStockEvent): DisableStockUiState = when (event) {
    is DisableStockEvent.Toggle -> copy(
        choices = choices.map { if (it.app.packageName == event.packageName) it.copy(selected = !it.selected) else it },
    )
    DisableStockEvent.Disable -> copy(running = true)
}

/** Shell-stage view model: the apps to turn off come straight from the catalogue; required apps start selected, optional ones start off. */
class DisableStockViewModel : ViewModel() {
    private val _state = MutableStateFlow(
        DisableStockUiState(
            choices = AppCatalogue.current.stockAppsToDisable
                .filter { it.device == Device.Watch }
                .map { StockAppChoice(it, selected = !it.optional) },
        ),
    )
    val state: StateFlow<DisableStockUiState> = _state.asStateFlow()

    fun onEvent(event: DisableStockEvent) = _state.update { it.reduce(event) }
}
