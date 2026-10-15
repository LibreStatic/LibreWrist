package io.openshm.patcher.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.data.ADVANCE_SECONDS_DEFAULT
import io.openshm.patcher.data.AppUpdatePrefs
import io.openshm.patcher.data.Automation
import io.openshm.patcher.update.AppUpdateScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val autoCheck: Boolean = true,
    /** False when this build has no update repository: the switch and the button are disabled. */
    val updatesAvailable: Boolean = true,
    /** A manual check is running. */
    val checking: Boolean = false,
    val versionName: String = "",
    val versionCode: Int = 0,
    /** Seconds the unattended countdown takes between steps. */
    val advanceSeconds: Int = ADVANCE_SECONDS_DEFAULT,
)

/** The values behind the Settings screen; the update checks themselves are in the activity-scoped update view model. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = AppUpdatePrefs(application)
    private val _state = MutableStateFlow(
        SettingsUiState(
            autoCheck = prefs.autoCheck,
            updatesAvailable = BuildConfig.UPDATE_REPO.isNotEmpty(),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            advanceSeconds = Automation.state.value.advanceSeconds,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            Automation.state.collect { automation -> _state.update { it.copy(advanceSeconds = automation.advanceSeconds) } }
        }
    }

    fun setAutoCheck(value: Boolean) {
        prefs.autoCheck = value
        AppUpdateScheduler.sync(getApplication())
        _state.value = _state.value.copy(autoCheck = value)
    }

    fun setAdvanceSeconds(value: Int) = Automation.setAdvanceSeconds(value)
}
