package io.openshm.patcher.ui.screens.profile

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.WatchAnnouncements
import io.openshm.patcher.device.WatchDetection
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.ui.nav.StepSkips
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Decides whether the Profile step moves on by itself: once, when the watch's version arrives while
 * the screen is open, unless the user chose manual setup or already picked an option here. A
 * detection that was there when the screen opened never advances (the user came back to it).
 */
internal class AutoAdvance(initiallyDetected: Boolean, private val manual: () -> Boolean) {
    private var armed = !initiallyDetected

    /** The user touched the selection: never advance. */
    fun touched() {
        armed = false
    }

    /** True the first time a detection [arrived] with nothing else holding the screen back. */
    fun detectionArrived(arrived: Boolean): Boolean {
        if (!arrived || !armed) return false
        armed = false
        return !manual()
    }
}

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ProfileUiState(selected = SetupSession.state.value.watchProfile))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val autoAdvance = AutoAdvance(
        initiallyDetected = WatchDetection.state.value.watch != null || WatchAnnouncements.state.value != null,
    ) { StepSkips.manual }
    private val _advance = Channel<Unit>(Channel.CONFLATED)
    private var pendingAdvance: Job? = null

    /** Fires once when the screen should move on by itself after showing the detected watch. */
    val advance: Flow<Unit> = _advance.receiveAsFlow()

    init {
        // A remembered watch answers authoritatively; any failure is silent (the user just picks).
        WatchDetection.start(WatchLink.get(application))
        viewModelScope.launch {
            WatchDetection.state.collect { detection ->
                val detected = detection.watch ?: return@collect
                // The ADB answer replaces a network announcement, never the other way round.
                if (_state.value.detected?.source == DetectedWatch.Source.Adb) return@collect
                setDetected(detected)
            }
        }
        viewModelScope.launch {
            WatchAnnouncements.state.collect { announced ->
                if (announced == null || _state.value.detected != null) return@collect
                setDetected(announced)
            }
        }
        viewModelScope.launch {
            WatchAnnouncements.running.collect { running -> _state.update { it.copy(scanning = running) } }
        }
    }

    /** The screen leaves the foreground: a pending automatic advance is dropped. */
    fun cancelAdvance() {
        pendingAdvance?.cancel()
        pendingAdvance = null
    }

    private fun setDetected(detected: DetectedWatch) {
        val first = _state.value.detected == null
        select(detected.profile)
        _state.update { it.copy(detected = detected) }
        if (autoAdvance.detectionArrived(first)) {
            // Long enough to read the detected card before the screen moves on.
            pendingAdvance = viewModelScope.launch {
                delay(ADVANCE_DELAY_MILLIS)
                _advance.trySend(Unit)
            }
        }
    }

    fun onEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.Select -> {
                autoAdvance.touched()
                cancelAdvance()
                select(event.profile)
            }
            // The host asks for the Bluetooth permission and reports back through onBluetoothPermission.
            ProfileEvent.CheckPairedWatches, ProfileEvent.AllowBluetooth -> Unit
        }
    }

    /** The result of the Bluetooth permission request (or its existing grant). */
    fun onBluetoothPermission(granted: Boolean) {
        if (!granted) {
            _state.update { it.copy(pairedCheck = PairedCheck.Denied, hint = null) }
            return
        }
        val names = bondedNames()
        val hint = WatchModelHint.best(names)
        _state.update { it.copy(pairedCheck = if (hint == null) PairedCheck.NoWatch else PairedCheck.Done, hint = hint) }
        // The watch itself is authoritative: a hint never overrides what it reported.
        if (hint is WatchModelHint.Certain && _state.value.detected == null) select(WatchProfile.WearOs5)
    }

    private fun select(profile: WatchProfile) {
        SetupSession.update { it.copy(watchProfile = profile) }
        _state.update { it.copy(selected = profile) }
    }

    private fun bondedNames(): List<String> = try {
        val adapter = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter
        adapter?.bondedDevices.orEmpty().mapNotNull { it.name }
    } catch (e: SecurityException) {
        emptyList()
    }

    private companion object {
        const val ADVANCE_DELAY_MILLIS = 1_200L
    }
}
