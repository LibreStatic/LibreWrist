package io.openshm.patcher.ui.screens.revert

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.AdbWatchDevice
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.SessionPhoneInstaller
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.WatchKeepAlive
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.screens.install.watchErrorRes
import io.openshm.patcher.ui.screens.selftest.PrefsSelfTestRecord
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the revert: probes the devices the scope involves (from the nav arguments in [savedState]), runs [RevertRunner] and keeps [RevertUiState] with the pure
 * reducer. The removal confirmations of the phone are launched through [launches] like the install screen does.
 */
class RevertViewModel(application: Application, savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val scope = RevertScope.parse(savedState.get<String>(Route.REVERT_SCOPE_ARG))

    /** Set when the user already confirmed on Home: the run starts by itself after the first probe. */
    private var autoStart = savedState.get<String>(Route.REVERT_CONFIRMED_ARG) == "true" && scope is RevertScope.Output

    private val _state = MutableStateFlow(RevertUiState(scope = scope))
    val state: StateFlow<RevertUiState> = _state.asStateFlow()

    private val outgoing = Channel<LaunchRequest>(Channel.BUFFERED)

    /** The system's removal confirmations; the nav host starts them from the activity. */
    val launches: Flow<LaunchRequest> = outgoing.receiveAsFlow()

    private val phone = SessionPhoneInstaller(application) { outgoing.trySend(LaunchRequest(it)) }
    private val keepAlive = WatchKeepAlive.get(application)
    private val runner = RevertRunner(
        phone = phone,
        watch = AdbWatchDevice(WatchLink.get(application)),
        outputs = AppCatalogue.current.outputs,
        stockApps = AppCatalogue.current.stockAppsToDisable,
        storedPrefix = SetupSession.state.value.packagePrefix,
        ownPackage = application.packageName,
        scope = scope,
        log = { Log.i(TAG, it) },
    )

    private var skipWatch = false
    private var cleaned = false
    private var startedKeepAlive = false

    init {
        probe()
    }

    fun onEvent(event: RevertEvent) {
        when (event) {
            RevertEvent.Reprobe -> {
                if (_state.value.phase == RevertPhase.Running) return
                skipWatch = false
                cleaned = false
                _state.update { it.reduce(event) }
                probe()
            }
            RevertEvent.SkipWatch -> {
                skipWatch = true
                reduce(event)
            }
            RevertEvent.Start -> start()
            RevertEvent.ConfirmSelfUninstall -> selfUninstall()
            else -> reduce(event)
        }
    }

    private fun reduce(event: RevertEvent) {
        _state.update { it.reduce(event) }
        finishWhenDone()
    }

    private fun probe() {
        viewModelScope.launch(Dispatchers.IO) {
            if (scope.involvesWatch(AppCatalogue.current.outputs) && !skipWatch && !keepAlive.isRunning) {
                startedKeepAlive = true
                keepAlive.start()
            }
            val result = runner.probe(includeWatch = !skipWatch)
            val error = (result.watch as? WatchReach.Unreachable)?.let { watchErrorRes(it.cause) }
            reduce(RevertEvent.Probed(result, error))
            // Only the first probe may start the run; an unreachable watch leaves the decision to the user.
            if (autoStart) {
                autoStart = false
                if (_state.value.canRevert) start()
            }
        }
    }

    private fun start() {
        val current = _state.value
        if (current.phase != RevertPhase.Review || current.rows.isEmpty()) return
        val items = current.rows.map { it.item }
        val covered = current.watch == WatchStatus.Reached || current.watch == WatchStatus.NotNeeded
        reduce(RevertEvent.Start)
        viewModelScope.launch(Dispatchers.IO) {
            val report = try {
                runner.run(items, covered) { item, outcome -> reduce(RevertEvent.Step(item.key, outcome)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // run() reports every step itself; this is a last line so the screen never hangs in Running.
                Log.w(TAG, "revert run failed: ${e.javaClass.simpleName}")
                RevertReport(emptyMap(), watchCovered = false)
            }
            reduce(RevertEvent.Finished(report))
        }
    }

    /** The explicit last step: asks Android to remove this app. A declined prompt just returns to the result. */
    private fun selfUninstall() {
        reduce(RevertEvent.ConfirmSelfUninstall)
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                phone.uninstall(getApplication<Application>().packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UninstallResult.Failure(e.message ?: e.javaClass.simpleName)
            }
            if (result is UninstallResult.Failure) {
                reduce(
                    RevertEvent.SelfUninstallEnded(
                        if (result.message.startsWith("STATUS_FAILURE_ABORTED")) SelfUninstallResult.Cancelled
                        else SelfUninstallResult.Failed(result.message),
                    ),
                )
            }
        }
    }

    /**
     * When the run is Done. The whole setup (scope [RevertScope.All], or a scoped run that left no active
     * output) starts over once complete: see [forgetSetup]. A scoped run only records the outputs it
     * removed in [SetupSession] and clears the self-test result, which is stale now; the build record
     * and the prefix lock stay so the user can install the apps again.
     */
    @Synchronized
    private fun finishWhenDone() {
        val s = _state.value
        if (cleaned || s.phase != RevertPhase.Done) return
        if (scope == RevertScope.All) {
            if (s.complete) forgetSetup()
            return
        }
        cleaned = true
        val restored = restoredOutputs(s)
        SetupSession.update { it.copy(restoredOutputs = it.restoredOutputs + restored) }
        clearStored(builds = false)
        if (SetupSession.state.value.activeOutputIds.isEmpty()) forgetSetup()
    }

    /** The outputs of the scope that are gone: none of their uninstall rows is left unsettled. */
    private fun restoredOutputs(s: RevertUiState): Set<String> {
        val inScope = when (scope) {
            RevertScope.All -> emptyList()
            RevertScope.Watch -> AppCatalogue.current.outputs
                .filter { it.device == Device.Watch && it.id in SetupSession.state.value.outputIds }.map { it.id }
            is RevertScope.Output -> listOf(scope.outputId)
        }
        val unsettled = s.rows
            .filter { it.item.action == RevertAction.Uninstall && it.outcome?.settled != true }
            .mapNotNullTo(mutableSetOf()) { it.item.outputId }
        return inScope.filter { it !in unsettled }.toSet()
    }

    /**
     * The setup starts over: the prefix is unlocked and the build record and the self-test result go.
     * The signing key, the downloaded inputs and the prefix stay.
     */
    private fun forgetSetup() {
        cleaned = true
        SetupSession.update { it.copy(prefixLocked = false, restoredOutputs = emptySet()) }
        clearStored(builds = true)
        _state.update { it.copy(setupForgotten = true) }
    }

    private fun clearStored(builds: Boolean) {
        val app = getApplication<Application>()
        try {
            if (builds) BuildStorage(app).clearBuilds()
            PrefsSelfTestRecord(app).clear()
        } catch (e: IOException) {
            Log.w(TAG, "could not clear the stored builds: ${e.javaClass.simpleName}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not clear the stored builds: ${e.javaClass.simpleName}")
        }
    }

    override fun onCleared() {
        // Only a keep-alive this screen started is its to stop; the build may be waiting on another one.
        if (startedKeepAlive) keepAlive.stop()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Revert"
    }
}
