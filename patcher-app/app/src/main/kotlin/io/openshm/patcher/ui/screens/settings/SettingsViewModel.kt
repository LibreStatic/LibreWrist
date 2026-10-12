package io.openshm.patcher.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.data.AppUpdatePrefs
import io.openshm.patcher.update.AppUpdateScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsUiState(
    val autoCheck: Boolean = true,
    /** False when this build has no update repository: the switch and the button are disabled. */
    val updatesAvailable: Boolean = true,
    /** A manual check is running. */
    val checking: Boolean = false,
    val versionName: String = "",
    val versionCode: Int = 0,
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
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun setAutoCheck(value: Boolean) {
        prefs.autoCheck = value
        AppUpdateScheduler.sync(getApplication())
        _state.value = _state.value.copy(autoCheck = value)
    }
}
