package io.openshm.patcher.ui.screens.wearable

import io.openshm.patcher.device.guide.GuideRequest
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
import io.openshm.patcher.ui.screens.install.NEEDS_PAIRING_SCREEN
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

    private var settingsOpened = false

    private val model = TroubleshootModel(
        scope = viewModelScope,
        io = Dispatchers.IO,
        startAdb = {
            settingsOpened = false
            SelfAdbService.start(application)
        },
        runClear = { it.shell(CLEAR_GMS_COMMAND) },
    )

    val state: StateFlow<TroubleshootUiState> = model.state

    init {
        viewModelScope.launch {
            SelfAdbController.state.collect { snapshot ->
                // Wireless debugging opens only when the stored pairing could not reconnect.
                if (snapshot.purpose == TroubleshootModel.PURPOSE && snapshot.phase in NEEDS_PAIRING_SCREEN && !settingsOpened) {
                    settingsOpened = true
                    outgoing.trySend(PhoneSettings.wirelessDebugging())
                }
                model.onAdb(snapshot)
            }
        }
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
    guide = GuideRequest.PlayServicesClearData,
)
