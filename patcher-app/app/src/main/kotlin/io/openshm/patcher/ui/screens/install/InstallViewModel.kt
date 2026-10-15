package io.openshm.patcher.ui.screens.install

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.adb.GrantApplier
import io.openshm.patcher.adb.GrantTarget
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.device.AndroidPhoneProbe
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.PhoneSettings
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbPurpose
import io.openshm.patcher.device.SelfAdbService
import io.openshm.patcher.device.SelfAdbSnapshot
import io.openshm.patcher.device.SessionPhoneInstaller
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.RebuildScope
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.AdbWatchDevice
import io.openshm.patcher.device.WatchKeepAlive
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SelfAdb"
private const val ADB_REREADS = 6
private const val ADB_REREAD_MS = 500L

/** Phases in which the user has to act in Wireless debugging (the stored pairing did not reconnect). */
internal val NEEDS_PAIRING_SCREEN = setOf(SelfAdbPhase.WaitingForService, SelfAdbPhase.Ambiguous, SelfAdbPhase.WaitingForCode)

private val USER_ONLY_ON_PHONE = setOf(GrantKind.Permission, GrantKind.AppOp, GrantKind.BatteryExemption)

/**
 * The install list for one device, in the catalogue `installOrder` (shm-watch, bridge-watch, alarm
 * fork on the watch), each with the grants the catalogue lists for it.
 */
internal fun installItemsFor(device: Device): List<InstallItem> {
    val session = SetupSession.state.value
    return AppCatalogue.current.outputs
        .filter { it.device == device && it.id in session.outputIds && RebuildScope.includes(it.id) }
        // A rebuild leaves out the outputs restored from Home: they stay out until installed again.
        .filter { RebuildScope.scope == null || it.id !in session.restoredOutputs }
        .map { out ->
            InstallItem(
                outputId = out.id,
                label = out.label,
                packageName = out.packageName(session.packagePrefix),
                status = CheckStatus.Pending,
                grants = AppCatalogue.current.grants[out.id].orEmpty().map {
                    // On the phone the app cannot apply permissions, app ops and the battery exemption; only the user (or self-ADB) can.
                    GrantRow(it, CheckStatus.Pending, manual = device == Device.Phone && it.kind in USER_ONLY_ON_PHONE)
                },
                onboarding = out.userOnboarding,
            )
        }
}

/**
 * Installs the built phone outputs with the platform installer and tracks the access they need; the
 * work is in [PhoneInstallRunner]. Intents for the system screens go out through [launches], which
 * the nav host starts from the activity (the installer's confirmation must come from there).
 */
class InstallPhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(InstallUiState(installItemsFor(Device.Phone)))
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    private val outgoing = Channel<LaunchRequest>(Channel.BUFFERED)

    /** Screens to start: Settings pages, the app's own setup, the installer's confirmation. */
    val launches: Flow<LaunchRequest> = outgoing.receiveAsFlow()

    private val runner = PhoneInstallRunner(
        installer = SessionPhoneInstaller(application) { outgoing.trySend(LaunchRequest(it)) },
        grants = PhoneGrants(AndroidPhoneProbe(application)),
        build = { BuildStorage(application).record.read() },
        prefix = { SetupSession.state.value.packagePrefix },
        lockPrefix = { SetupSession.update { it.copy(prefixLocked = true) } },
        onInstalled = { id -> SetupSession.update { it.copy(restoredOutputs = it.restoredOutputs - id) } },
        log = { Log.w("BridgeStart", it) },
        outputs = AppCatalogue.current.outputs,
        ownPackage = application.packageName,
    )

    init {
        viewModelScope.launch { SelfAdbController.state.collect(::onAdb) }
    }

    fun onEvent(event: InstallEvent) {
        when (event) {
            InstallEvent.Start -> start()
            InstallEvent.Refresh -> runner.refresh(_state)
            is InstallEvent.OpenGrantSettings -> openSettings(event)
            InstallEvent.OpenUnknownSources -> outgoing.trySend(PhoneSettings.unknownSources(getApplication()))
            is InstallEvent.OpenApp -> openApp(event.outputId)
            InstallEvent.GrantWithAdb -> startAdb()
            is InstallEvent.SubmitPairingCode -> SelfAdbController.submitCode(event.code)
            InstallEvent.CancelAdb -> SelfAdbController.finish()
            InstallEvent.OpenWirelessDebugging -> outgoing.trySend(PhoneSettings.wirelessDebugging())
            InstallEvent.RequestReinstall -> requestReinstall(_state)
            InstallEvent.CancelReinstall -> cancelReinstall(_state)
            InstallEvent.ConfirmReinstall -> reinstall()
            InstallEvent.AcknowledgeAdbOutcome -> _state.update { it.copy(adbOutcome = null) }
        }
    }

    private fun reinstall() {
        if (_state.value.running || _state.value.conflict == null) return
        _state.update { it.copy(running = true, confirmingReinstall = false) }
        viewModelScope.launch(Dispatchers.IO) { runner.reinstall(_state) }
    }

    private fun start() {
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        viewModelScope.launch(Dispatchers.IO) { runner.run(_state) }
    }

    private fun openSettings(event: InstallEvent.OpenGrantSettings) {
        val item = _state.value.items.firstOrNull { it.outputId == event.outputId } ?: return
        val grant = item.grants.firstOrNull { it.grant.name == event.grantName }?.grant ?: return
        val target = PhoneGrants.targetFor(grant)
        outgoing.trySend(PhoneSettings.intentFor(target, item.packageName, PhoneGrants.componentOf(item.packageName, grant)))
    }

    private fun openApp(outputId: String) {
        val item = _state.value.items.firstOrNull { it.outputId == outputId } ?: return
        getApplication<Application>().packageManager.getLaunchIntentForPackage(item.packageName)
            ?.let { outgoing.trySend(LaunchRequest(it)) }
    }

    private fun startAdb() {
        if (_state.value.running || !SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)) return
        _state.update { it.copy(errorRes = null, errorDetail = null) }
        settingsOpened = false
        SelfAdbService.start(getApplication())
    }

    /**
     * Wireless debugging opens only once the stored pairing could not reconnect: with a pairing
     * that still works the grants are applied without leaving this screen.
     */
    private var settingsOpened = false

    /**
     * Follows [SelfAdbController], which outlives this view model. Once connected, one view model
     * takes the connection and applies the grants; the attempt then ends (and the service stops),
     * also when that work is cancelled.
     */
    private fun onAdb(snapshot: SelfAdbSnapshot) {
        // An attempt of another screen (troubleshooting) is not ours to show or to take.
        if (snapshot.purpose != null && snapshot.purpose != SelfAdbPurpose.PhoneGrants) {
            _state.update { it.copy(selfAdb = null) }
            return
        }
        _state.update { it.copy(selfAdb = snapshot.phase) }
        if (snapshot.phase in NEEDS_PAIRING_SCREEN && !settingsOpened) {
            settingsOpened = true
            outgoing.trySend(PhoneSettings.wirelessDebugging())
        }
        if (snapshot.phase == SelfAdbPhase.Failed) {
            if (snapshot.needsNotifications) fail(_state, R.string.install_error_notifications, null)
            else {
                fail(_state, R.string.phone_adb_failed, snapshot.error)
                _state.update { it.copy(adbOutcome = AdbOutcome.Failure(snapshot.error)) }
            }
        }
        if (snapshot.phase == SelfAdbPhase.Granting) {
            val client = SelfAdbController.takeClient(SelfAdbPurpose.PhoneGrants) ?: return
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val ok = runner.grantWithAdb(_state) { pkg, grants ->
                        GrantApplier(client, GrantTarget.PHONE).apply(pkg, grants).also { report ->
                            Log.i(TAG, "grants for $pkg: ${report.outcomes.count { it.applied }} of ${report.outcomes.size} applied")
                        }
                    }
                    _state.value.items.forEach { item ->
                        Log.i(TAG, "after refresh ${item.packageName}: ${item.grants.joinToString { "${it.grant.name}=${it.status}" }}")
                    }
                    var outcome = adbOutcomeOf(ok, _state.value.items, _state.value.errorDetail)
                    // The system persists some grants (the notification listener) a moment after the
                    // shell command returns, so a grant can read as missing right after the run.
                    var rereads = 0
                    while (ok && outcome is AdbOutcome.Failure && rereads++ < ADB_REREADS) {
                        delay(ADB_REREAD_MS)
                        runner.refresh(_state)
                        outcome = adbOutcomeOf(ok, _state.value.items, _state.value.errorDetail)
                    }
                    _state.update { it.copy(adbOutcome = outcome) }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "grant run failed", e)
                    _state.update { it.copy(adbOutcome = AdbOutcome.Failure(e.toString())) }
                } finally {
                    SelfAdbController.finish()
                }
            }
        }
    }
}

/** Installs the built watch outputs over ADB and applies their grants; the work is in [WatchInstallRunner]. */
class InstallWatchViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(InstallUiState(installItemsFor(Device.Watch)))
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    private val runner = WatchInstallRunner(
        device = AdbWatchDevice(WatchLink.get(application)),
        build = { BuildStorage(application).record.read() },
        prefix = { SetupSession.state.value.packagePrefix },
        lockPrefix = { SetupSession.update { it.copy(prefixLocked = true) } },
        onInstalled = { id -> SetupSession.update { it.copy(restoredOutputs = it.restoredOutputs - id) } },
        log = { Log.w("BridgeStart", it) },
        outputs = AppCatalogue.current.outputs,
        ownPackage = application.packageName,
    )

    init {
        // The keep-alive of the build only matters until the watch install is complete.
        viewModelScope.launch {
            _state.first { it.done }
            WatchKeepAlive.get(application).stop()
        }
    }

    fun onEvent(event: InstallEvent) {
        when (event) {
            InstallEvent.Start -> start()
            InstallEvent.RequestReinstall -> requestReinstall(_state)
            InstallEvent.CancelReinstall -> cancelReinstall(_state)
            InstallEvent.ConfirmReinstall -> reinstall()
            else -> Unit
        }
    }

    private fun reinstall() {
        if (_state.value.running || _state.value.conflict == null) return
        _state.update { it.copy(running = true, confirmingReinstall = false) }
        viewModelScope.launch(Dispatchers.IO) { runner.reinstall(_state) }
    }

    private fun start() {
        if (_state.value.running) return
        _state.update { it.copy(running = true) }
        viewModelScope.launch(Dispatchers.IO) { runner.run(_state) }
    }
}
