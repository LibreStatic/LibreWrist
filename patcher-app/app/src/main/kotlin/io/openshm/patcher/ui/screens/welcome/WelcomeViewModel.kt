package io.openshm.patcher.ui.screens.welcome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.AdbPresence
import io.openshm.patcher.device.WatchAnnouncements
import io.openshm.patcher.device.WatchDetection
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.ui.nav.StepSkips
import io.openshm.patcher.ui.screens.wearable.readWearableFacts
import io.openshm.patcher.ui.screens.wearable.wearableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the build installs, grants and turns off on each device, from the catalogue and the chosen prefix. */
internal fun buildChanges(prefix: String, profile: WatchProfile, samsungHealth: Boolean = true): List<DeviceChanges> {
    val catalogue = AppCatalogue.current
    val ids = SessionState(watchProfile = profile, samsungHealth = samsungHealth).outputIds
    val outputs = catalogue.outputs.filter { it.id in ids }
    return Device.entries.map { device ->
        DeviceChanges(
            device = device,
            apps = outputs.filter { it.device == device }.map { out ->
                AppChange(out.id, out.label, out.packageName(prefix), catalogue.grants[out.id].orEmpty())
            },
            disables = catalogue.stockAppsToDisable.filter { it.device == device },
        )
    }
}

class WelcomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(
        SetupSession.state.value.let { WelcomeUiState(changes = buildChanges(it.packagePrefix, it.watchProfile, it.samsungHealth)) },
    )
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()
    private val link = WatchLink.get(application)

    private val preflight = SkipPreflight(
        viewModelScope,
        watchRemembered = { link.rememberedHost != null },
        detect = {
            WatchDetection.start(link)
            withTimeoutOrNull(DETECT_WAIT_MILLIS) { WatchDetection.state.first { !it.running } }?.watch
        },
        wearableReady = { withContext(Dispatchers.Default) { wearableState(readWearableFacts(application)).allPassed } },
        apply = { steps, watch ->
            StepSkips.current = steps
            if (watch != null) SetupSession.update { it.copy(watchProfile = watch.profile) }
        },
        announced = { WatchAnnouncements.state.value },
        setManual = { StepSkips.manual = it },
    )

    /** Open the next step that still needs the user; the nav host follows it. */
    val go: Flow<Unit> = preflight.go

    init {
        // Ask a remembered watch for its version now, so it is known by the time the user starts.
        WatchDetection.start(link)
        // The undo link shows while the setup has installed something and hides again after a complete revert.
        viewModelScope.launch {
            SetupSession.state.map { it.prefixLocked }.distinctUntilChanged()
                .collect { locked -> _state.update { it.copy(canRevert = locked) } }
        }
        viewModelScope.launch { preflight.dialog.collect { d -> _state.update { it.copy(skip = d) } } }
        // The device logs link shows once the watch is known to answer over ADB, now or when it announces itself.
        viewModelScope.launch {
            combine(WatchDetection.state, AdbPresence.state) { detection, presence ->
                detection.watch != null || presence.watchAvailable
            }.distinctUntilChanged().collect { reachable -> _state.update { it.copy(watchReachable = reachable) } }
        }
    }

    fun onEvent(event: WelcomeEvent) {
        when (event) {
            WelcomeEvent.ShowChanges -> _state.update { it.copy(changesSheetVisible = true) }
            WelcomeEvent.HideChanges -> _state.update { it.copy(changesSheetVisible = false) }
            WelcomeEvent.GetStarted -> preflight.start()
            WelcomeEvent.ConfirmSkip -> preflight.confirm()
            WelcomeEvent.CancelSkip -> preflight.cancel()
            WelcomeEvent.ManualSetup -> preflight.manual()
        }
    }

    private companion object {
        /** A bit over the detector's own connect timeout. */
        const val DETECT_WAIT_MILLIS = 6_000L
    }
}
