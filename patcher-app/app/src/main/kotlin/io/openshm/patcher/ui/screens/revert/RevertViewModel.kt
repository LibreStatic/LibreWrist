package io.openshm.patcher.ui.screens.revert

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.AdbWatchDevice
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.SessionPhoneInstaller
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.WatchKeepAlive
import io.openshm.patcher.device.WatchLink
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
 * Drives the revert: probes both devices, runs [RevertRunner] and keeps [RevertUiState] with the pure
 * reducer. The removal confirmations of the phone are launched through [launches] like the install screen does.
 */
class RevertViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(RevertUiState())
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
        forgetSetupWhenComplete()
    }

    private fun probe() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!skipWatch && !keepAlive.isRunning) {
                startedKeepAlive = true
                keepAlive.start()
            }
            val result = runner.probe(includeWatch = !skipWatch)
            val error = (result.watch as? WatchReach.Unreachable)?.let { watchErrorRes(it.cause) }
            reduce(RevertEvent.Probed(result, error))
        }
    }

    private fun start() {
        val current = _state.value
        if (current.phase != RevertPhase.Review || current.rows.isEmpty()) return
        val items = current.rows.map { it.item }
        val covered = current.watch == WatchStatus.Reached
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
     * After a complete revert the setup starts over: the prefix is unlocked and the build record and
     * the self-test result go. The signing key, the downloaded inputs and the prefix stay.
     */
    @Synchronized
    private fun forgetSetupWhenComplete() {
        val s = _state.value
        if (cleaned || s.phase != RevertPhase.Done || !s.complete) return
        cleaned = true
        SetupSession.update { it.copy(prefixLocked = false) }
        val app = getApplication<Application>()
        try {
            BuildStorage(app).clearBuilds()
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
