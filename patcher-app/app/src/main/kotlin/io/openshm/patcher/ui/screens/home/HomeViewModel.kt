package io.openshm.patcher.ui.screens.home

import androidx.lifecycle.ViewModel
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal fun HomeUiState.reduce(event: HomeEvent): HomeUiState = when (event) {
    HomeEvent.ToggleMenu -> copy(menuOpen = !menuOpen)
    is HomeEvent.AskRestore -> copy(restoreSheetVisible = true)
    HomeEvent.ConfirmRestore, HomeEvent.DismissRestore -> copy(restoreSheetVisible = false)
    HomeEvent.NewBuild, HomeEvent.RunSelfTest, HomeEvent.RestoreWatch, HomeEvent.Uninstall -> copy(menuOpen = false)
    else -> this
}

/**
 * Shell-stage view model: the installed builds come from the catalogue and the chosen prefix. The
 * persisted install record and the last self-test result replace [initial] when storage lands.
 */
class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun onEvent(event: HomeEvent) = _state.update { it.reduce(event) }

    private fun initial(): HomeUiState {
        val session = SetupSession.state.value
        return HomeUiState(
            status = HomeStatus(CheckStatus.Passed, daysAgo = 0),
            builds = AppCatalogue.current.outputs
                .filter { it.id in session.watchProfile.outputIds }
                .map { InstalledBuild(it.id, it.label, it.packageName(session.packagePrefix), it.device) },
        )
    }
}
