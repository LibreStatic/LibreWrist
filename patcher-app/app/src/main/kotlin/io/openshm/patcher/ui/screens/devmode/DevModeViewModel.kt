package io.openshm.patcher.ui.screens.devmode

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.device.HealthPlatformDevMode
import io.openshm.patcher.device.WatchLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Health Platform developer mode: [DevModeEvent.Enable] turns it on through the watch's UI, and while
 * the screen is shown a background watch catches the user turning it on by hand (a cheap poll of
 * the front activity, and a view-hierarchy read only while the Health Platform settings are in front).
 */
class DevModeViewModel(application: Application) : AndroidViewModel(application) {

    private val probe = HealthPlatformDevMode { WatchLink.get(application).client() }

    /** One watch command at a time: the poll and the buttons share the connection. */
    private val watchLock = Mutex()

    private val _state = MutableStateFlow(DevModeUiState())
    val state: StateFlow<DevModeUiState> = _state.asStateFlow()

    private var watcher: Job? = null

    fun onEvent(event: DevModeEvent) {
        when (event) {
            DevModeEvent.OpenHealthPlatformSettings -> viewModelScope.launch { open() }
            DevModeEvent.Enable -> viewModelScope.launch { enable() }
            DevModeEvent.DismissNotice -> _state.update { it.copy(notice = null) }
            DevModeEvent.Celebrated -> _state.update { it.copy(celebrate = false) }
        }
    }

    /** Starts the background watch; called while the screen is visible. */
    fun startWatching() {
        if (watcher?.isActive == true || _state.value.detected) return
        watcher = viewModelScope.launch {
            _state.update { it.copy(watching = true) }
            try {
                while (isActive && !_state.value.detected) {
                    val delayMs = try {
                        if (poll()) {
                            onDetected()
                            break
                        }
                        _state.update { it.copy(watchReachable = true) }
                        POLL_MS
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.i(TAG, "watch poll failed: ${e.javaClass.simpleName}: ${e.message}")
                        _state.update { it.copy(watchReachable = false) }
                        RETRY_MS
                    }
                    delay(delayMs)
                }
            } finally {
                _state.update { it.copy(watching = false) }
            }
        }
    }

    /** Stops the background watch; called when the screen leaves the foreground. */
    fun stopWatching() {
        watcher?.cancel()
        watcher = null
    }

    private suspend fun poll(): Boolean = watchLock.withLock {
        probe.settingsInFront() && probe.readScreen().devModeShown
    }

    private suspend fun open() {
        try {
            watchLock.withLock { probe.openSettings() }
            _state.update { it.copy(notice = DevModeNotice.LookAtWatch, watchReachable = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "open settings failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.update { it.copy(notice = DevModeNotice.Unreachable) }
        }
    }

    private suspend fun enable() {
        _state.update { it.copy(checking = true) }
        try {
            if (watchLock.withLock { probe.enable() }) {
                onDetected()
            } else {
                _state.update { it.copy(notice = DevModeNotice.NotYet) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HealthPlatformDevMode.TitleNotFound) {
            Log.i(TAG, "enable: ${e.message}")
            _state.update { it.copy(notice = DevModeNotice.NotYet) }
        } catch (e: Exception) {
            Log.i(TAG, "enable failed: ${e.javaClass.simpleName}: ${e.message}")
            _state.update { it.copy(notice = DevModeNotice.Unreachable) }
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    private fun onDetected() {
        Log.i(TAG, "developer mode seen on the watch")
        _state.update {
            if (it.detected) it else it.copy(detected = true, notice = null, celebrate = true, watchReachable = true)
        }
    }

    private companion object {
        const val TAG = "DevMode"
        const val POLL_MS = 1_500L
        const val RETRY_MS = 5_000L
    }
}
