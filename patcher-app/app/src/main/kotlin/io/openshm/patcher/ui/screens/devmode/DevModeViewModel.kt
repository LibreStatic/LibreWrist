package io.openshm.patcher.ui.screens.devmode

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.AppCatalogue
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
 * Enable leaves the watch on the Health Platform settings; [onContinue] takes it back to the screen
 * it was on before, if the settings are still in front by then.
 */
class DevModeViewModel(application: Application) : AndroidViewModel(application) {

    private val platform = AppCatalogue.current.platform.healthPlatform
    private val probe = HealthPlatformDevMode(platform) { WatchLink.get(application).client() }

    /** One watch command at a time: the poll and the buttons share the connection. */
    private val watchLock = Mutex()

    private val _state = MutableStateFlow(DevModeUiState())
    val state: StateFlow<DevModeUiState> = _state.asStateFlow()

    private var watcher: Job? = null

    /** What was in front on the watch before Enable opened the Health Platform settings, until Continue. */
    private var returnTo: String? = null

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

    /**
     * Called when the user moves on from the screen: if Enable opened the Health Platform settings
     * and they are still in front, goes back to what the watch showed before. Runs in the background
     * so navigation is not held up.
     */
    fun onContinue() {
        val previous = returnTo ?: return
        returnTo = null
        viewModelScope.launch {
            try {
                val left = watchLock.withLock { probe.leaveSettings() }
                Log.i(TAG, if (left) "left the Health Platform settings for $previous" else "Health Platform settings still in front")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "leave settings failed: ${e.javaClass.simpleName}: ${e.message}")
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
            if (watchLock.withLock { rememberFront(); probe.enable() }) {
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

    /** Remembers the watch's front screen before the first Enable, unless it is Health Platform already. */
    private suspend fun rememberFront() {
        if (returnTo != null) return
        val front = try {
            probe.frontActivity()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "front activity read failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (front != null && !front.startsWith("${platform.packageName}/")) returnTo = front
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
