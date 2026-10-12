package io.openshm.patcher.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.CatalogueUpdates
import io.openshm.patcher.data.UpdateResult
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.selftest.PrefsSelfTestRecord
import io.openshm.patcher.ui.screens.selftest.SelfTestRecord
import io.openshm.patcher.ui.screens.selftest.SelfTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun HomeUiState.reduce(event: HomeEvent): HomeUiState = when (event) {
    HomeEvent.ToggleMenu -> copy(menuOpen = !menuOpen)
    is HomeEvent.AskRestore -> copy(restoreSheetVisible = true)
    HomeEvent.ConfirmRestore, HomeEvent.DismissRestore -> copy(restoreSheetVisible = false)
    HomeEvent.NewBuild, HomeEvent.CheckCatalogue, HomeEvent.RunSelfTest, HomeEvent.RestoreWatch, HomeEvent.Uninstall, HomeEvent.OpenSettings -> copy(menuOpen = false)
    HomeEvent.MessageShown -> copy(catalogueMessage = null)
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

    fun onEvent(event: HomeEvent) {
        _state.update { it.reduce(event) }
        if (event is HomeEvent.CheckCatalogue) checkCatalogue(force = true)
    }

    /**
     * Called when Home is shown: adopts an accepted catalogue when no build runs, re-reads the
     * self-test and the update offer, and starts the daily catalogue check.
     */
    fun refresh() {
        CatalogueUpdates.adoptPendingIfIdle(getApplication())
        _state.update { snapshot(it) }
        checkCatalogue(force = false)
    }

    private fun checkCatalogue(force: Boolean) {
        if (_state.value.checkingCatalogue) return
        _state.update { it.copy(checkingCatalogue = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    CatalogueUpdates.check(getApplication(), force)
                } catch (e: Exception) {
                    UpdateResult.Failed(e.message ?: e.javaClass.simpleName)
                }
            }
            CatalogueUpdates.adoptPendingIfIdle(getApplication())
            _state.update {
                // Only a check the user asked for reports trouble; the daily one only announces an update.
                val message = if (force || result is UpdateResult.Updated) messageFor(result) else null
                snapshot(it).copy(checkingCatalogue = false, catalogueMessage = message ?: it.catalogueMessage)
            }
        }
    }

    private fun messageFor(result: UpdateResult?): CatalogueMessage? {
        val id = System.nanoTime()
        return when (result) {
            null -> null
            is UpdateResult.Updated -> CatalogueMessage(R.string.home_catalogue_updated, result.serial, id)
            UpdateResult.UpToDate -> CatalogueMessage(R.string.home_catalogue_up_to_date, id = id)
            is UpdateResult.Rejected -> CatalogueMessage(R.string.home_catalogue_rejected, id = id)
            is UpdateResult.Failed -> CatalogueMessage(R.string.home_catalogue_failed, id = id)
            UpdateResult.Disabled -> CatalogueMessage(R.string.home_catalogue_disabled, id = id)
        }
    }

    /** The parts of Home that depend on the self-test, the loaded catalogue and the last build. */
    private fun snapshot(current: HomeUiState): HomeUiState {
        val session = SetupSession.state.value
        val outputIds = session.watchProfile.outputIds
        return current.copy(
            status = homeStatusOf(record.last(), System.currentTimeMillis()),
            update = updateOfferFor(AppCatalogue.current, BuildStorage(getApplication()).record.read(), outputIds),
            builds = AppCatalogue.current.outputs
                .filter { it.id in outputIds }
                .map { InstalledBuild(it.id, it.label, it.packageName(session.packagePrefix), it.device) },
        )
    }

    private fun initial(): HomeUiState = snapshot(HomeUiState())
}
