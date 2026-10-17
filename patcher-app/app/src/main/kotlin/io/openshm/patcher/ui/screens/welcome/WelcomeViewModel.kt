package io.openshm.patcher.ui.screens.welcome

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.InputStore
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
internal fun buildChanges(prefix: String, profile: WatchProfile, skippedApps: Set<String> = emptySet()): List<DeviceChanges> {
    val catalogue = AppCatalogue.current
    val ids = SessionState(watchProfile = profile, skippedApps = skippedApps).outputIds
    val outputs = catalogue.outputs.filter { it.id in ids }
    return Device.entries.map { device ->
        DeviceChanges(
            device = device,
            apps = outputs.filter { it.device == device }.map { out ->
                AppChange(out.id, out.label, out.packageName(prefix), catalogue.grants[out.id].orEmpty(), out.purpose, catalogue.appOf(out.id)?.icon.orEmpty())
            },
            disables = catalogue.stockAppsToDisable.filter { it.device == device },
        )
    }
}

class WelcomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(
        SetupSession.state.value.let { WelcomeUiState(changes = buildChanges(it.packagePrefix, it.watchProfile, it.skippedApps), automation = Automation.state.value) },
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
        wearableReady = { withContext(Dispatchers.Default) { AppCatalogue.current.platform.companionApp.let { wearableState(readWearableFacts(application, it), it) }.allPassed } },
        apply = { steps, watch ->
            StepSkips.current = steps
            if (watch != null) SetupSession.update { it.copy(watchProfile = watch.profile) }
        },
        announced = { WatchAnnouncements.state.value },
        setManual = { StepSkips.manual = it },
        autoSkip = { Automation.state.value.unattended },
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
        viewModelScope.launch { Automation.state.collect { a -> _state.update { it.copy(automation = a) } } }
        // The apps notice only matters while an official app this setup needs is still missing.
        val inputs = InputStore(application)
        val missingInputs = combine(SetupSession.state, Automation.state) { s, _ -> s.inputIds }.distinctUntilChanged().map { ids ->
            withContext(Dispatchers.IO) {
                ids.filter(AppCatalogue.current::hasInput).any { inputs.verifiedSize(AppCatalogue.current.input(it)) == null }
            }
        }
        viewModelScope.launch {
            combine(Automation.state, missingInputs) { a, missing -> a.appsNoticePending && missing }
                .collect { show -> _state.update { it.copy(appsNotice = show) } }
        }
        viewModelScope.launch { preflight.dialog.collect { d -> _state.update { it.copy(skip = d) } } }
        refreshLogsLink()
    }

    /**
     * Shows the device logs link once a device was paired. Whether it answers right now is left to the logs
     * screen, which connects and says what is missing: an idle watch does not answer mDNS, so waiting for it
     * here kept the link hidden.
     */
    fun refreshLogsLink() {
        _state.update { it.copy(logsAvailable = AdbPresence.hasEverPaired(getApplication())) }
    }

    fun onEvent(event: WelcomeEvent) {
        when (event) {
            WelcomeEvent.ShowChanges -> _state.update { it.copy(changesSheetVisible = true) }
            WelcomeEvent.HideChanges -> _state.update { it.copy(changesSheetVisible = false) }
            WelcomeEvent.GetStarted -> preflight.start()
            WelcomeEvent.ConfirmSkip -> preflight.confirm()
            WelcomeEvent.CancelSkip -> preflight.cancel()
            WelcomeEvent.ManualSetup -> preflight.manual()
            is WelcomeEvent.SetGuidePet -> Automation.setGuidePet(event.on)
            is WelcomeEvent.SetChinaMode -> Automation.setChinaMode(event.on)
            is WelcomeEvent.SetUnattended -> Automation.setUnattended(event.on)
            is WelcomeEvent.SetFreeRun ->
                // Turning it on asks first unless the user already chose not to be asked again.
                if (event.on && !Automation.state.value.freeRunWarningHidden) {
                    _state.update { it.copy(freeRunWarning = true) }
                } else {
                    Automation.setFreeRun(event.on)
                }
            is WelcomeEvent.SetUninstallMismatch -> Automation.setUninstallMismatch(event.on)
            is WelcomeEvent.ShowHelp -> _state.update { it.copy(help = event.help) }
            WelcomeEvent.HideHelp -> _state.update { it.copy(help = null) }
            is WelcomeEvent.ConfirmFreeRun -> {
                if (event.dontShowAgain) Automation.hideFreeRunWarning()
                Automation.setFreeRun(true)
                _state.update { it.copy(freeRunWarning = false) }
            }
            WelcomeEvent.CancelFreeRun -> _state.update { it.copy(freeRunWarning = false) }
            WelcomeEvent.AcknowledgeAppsNotice -> Automation.acknowledgeAppsNotice()
        }
    }

    private companion object {
        /** A bit over the detector's own connect timeout. */
        const val DETECT_WAIT_MILLIS = 6_000L
    }
}
