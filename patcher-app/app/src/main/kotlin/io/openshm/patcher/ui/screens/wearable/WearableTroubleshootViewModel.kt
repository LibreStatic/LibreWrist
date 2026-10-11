package io.openshm.patcher.ui.screens.wearable

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.PhoneSettings
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Troubleshooting for a watch that will not pair: manual and ADB-assisted reset of Google Play
 * services, with the logic in [TroubleshootModel]. Intents go out through [launches].
 */
class WearableTroubleshootViewModel(application: Application) : AndroidViewModel(application) {
    private val outgoing = Channel<LaunchRequest>(Channel.BUFFERED)

    /** Settings pages to start, from the activity. */
    val launches: Flow<LaunchRequest> = outgoing.receiveAsFlow()

    private val model = TroubleshootModel(
        scope = viewModelScope,
        io = Dispatchers.IO,
        startAdb = {
            SelfAdbService.start(application)
            outgoing.trySend(PhoneSettings.wirelessDebugging())
        },
        runClear = { it.shell(CLEAR_GMS_COMMAND) },
    )

    val state: StateFlow<TroubleshootUiState> = model.state

    init {
        viewModelScope.launch { SelfAdbController.state.collect(model::onAdb) }
    }

    fun onEvent(event: TroubleshootEvent) {
        if (event == TroubleshootEvent.OpenPlayServicesSettings) {
            outgoing.trySend(playServicesSettings())
        } else {
            model.onEvent(event)
        }
    }

    override fun onCleared() = model.onCleared()
}

internal fun playServicesSettings() = LaunchRequest(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$GMS_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)
