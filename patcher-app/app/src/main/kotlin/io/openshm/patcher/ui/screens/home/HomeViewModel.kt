package io.openshm.patcher.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.selftest.PrefsSelfTestRecord
import io.openshm.patcher.ui.screens.selftest.SelfTestRecord
import io.openshm.patcher.ui.screens.selftest.SelfTestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal fun HomeUiState.reduce(event: HomeEvent): HomeUiState = when (event) {
    HomeEvent.ToggleMenu -> copy(menuOpen = !menuOpen)
    is HomeEvent.AskRestore -> copy(restoreSheetVisible = true)
    HomeEvent.ConfirmRestore, HomeEvent.DismissRestore -> copy(restoreSheetVisible = false)
    HomeEvent.NewBuild, HomeEvent.RunSelfTest, HomeEvent.RestoreWatch, HomeEvent.Uninstall, HomeEvent.OpenSigningKey -> copy(menuOpen = false)
    else -> this
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** The status card for the last finished self-test; "not tested yet" when there is none. */
internal fun homeStatusOf(last: SelfTestResult?, nowMillis: Long): HomeStatus =
    if (last == null) HomeStatus(CheckStatus.Pending, daysAgo = null)
    else HomeStatus(last.overall, daysAgo = ((nowMillis - last.timestampMillis) / DAY_MS).toInt().coerceAtLeast(0))

/**
 * Shell-stage view model: the installed builds come from the catalogue and the chosen prefix, the
 * status card from the last persisted self-test. The persisted install record replaces the build
 * list when storage lands.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val record: SelfTestRecord = PrefsSelfTestRecord(application)
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun onEvent(event: HomeEvent) = _state.update { it.reduce(event) }

    /** Reads the last self-test again (it may have just finished on the other screen). */
    fun refresh() = _state.update { it.copy(status = homeStatusOf(record.last(), System.currentTimeMillis())) }

    private fun initial(): HomeUiState {
        val session = SetupSession.state.value
        return HomeUiState(
            status = homeStatusOf(record.last(), System.currentTimeMillis()),
            builds = AppCatalogue.current.outputs
                .filter { it.id in session.watchProfile.outputIds }
                .map { InstalledBuild(it.id, it.label, it.packageName(session.packagePrefix), it.device) },
        )
    }
}
