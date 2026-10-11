package io.openshm.patcher.ui.screens.profile

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.WatchAnnouncement
import io.openshm.patcher.device.WatchDetection
import io.openshm.patcher.device.WatchLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ProfileUiState(selected = SetupSession.state.value.watchProfile))
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

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
    }

    private var scan: Job? = null

    /**
     * Listens for the watch's Wireless debugging announcement (it carries the Android API level
     * before any pairing) until one names a watch. Silent on any failure. Runs only while the screen
     * is shown: the view model outlives it on the back stack.
     */
    fun startScan() {
        if (_state.value.detected != null || scan?.isActive == true) return
        val application = getApplication<Application>()
        _state.update { it.copy(scanning = true) }
        scan = viewModelScope.launch {
            try {
                val discovery = AdbDiscovery(application)
                val found = merge(announced(discovery, AdbServiceType.CONNECT), announced(discovery, AdbServiceType.PAIRING))
                    .filterNotNull()
                    .first()
                // ADB detection may have answered meanwhile and stays authoritative.
                if (_state.value.detected == null) setDetected(found)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent: the user simply picks.
            } finally {
                // A scan restarted meanwhile owns the flag.
                if (scan == null || scan === coroutineContext[Job]) _state.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() {
        scan?.cancel()
        scan = null
    }

    private fun announced(discovery: AdbDiscovery, type: AdbServiceType): Flow<DetectedWatch?> =
        discovery.discover(type).map(WatchAnnouncement::best).catch { }

    private fun setDetected(detected: DetectedWatch) {
        select(detected.profile)
        _state.update { it.copy(detected = detected) }
    }

    fun onEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.Select -> select(event.profile)
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
}
