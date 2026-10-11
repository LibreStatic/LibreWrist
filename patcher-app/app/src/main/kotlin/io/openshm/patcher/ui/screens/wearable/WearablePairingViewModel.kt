package io.openshm.patcher.ui.screens.wearable

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Looks at the phone for Galaxy Wearable, its watch plugin and a bonded watch. Nothing is changed;
 * [recheck] runs on entry, on resume (coming back from Galaxy Wearable) and on request.
 */
class WearablePairingViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(WearablePairingUiState())
    val state: StateFlow<WearablePairingUiState> = _state.asStateFlow()

    init {
        recheck()
    }

    fun onEvent(event: WearablePairingEvent) {
        when (event) {
            WearablePairingEvent.Recheck -> recheck()
            WearablePairingEvent.OpenGalaxyWearable -> open()
            WearablePairingEvent.GetGalaxyWearable -> store()
            // Needs an activity to ask; the host launches the request and calls recheck() after it.
            WearablePairingEvent.AllowBluetooth -> Unit
            // Navigation: the nav host handles it.
            WearablePairingEvent.Troubleshoot -> Unit
        }
    }

    fun recheck() {
        viewModelScope.launch(Dispatchers.Default) { _state.value = wearableState(readFacts()) }
    }

    private fun readFacts(): WearableFacts {
        val context = getApplication<Application>()
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val bonded = if (allowed) {
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                    .map { BondedDevice(it.name, it.bluetoothClass?.majorDeviceClass) }
            } catch (e: SecurityException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val installed = listOf(GALAXY_WEARABLE_PACKAGE, WATCH_PLUGIN_PACKAGE).filterTo(mutableSetOf(), ::isInstalled)
        return WearableFacts(installed, allowed, bonded)
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(packageName: String): Boolean = try {
        val pm = getApplication<Application>().packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(packageName, 0)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun open() {
        val context = getApplication<Application>()
        context.packageManager.getLaunchIntentForPackage(GALAXY_WEARABLE_PACKAGE)
            ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** The store app first, the store's web page when no store app handles `market:`. */
    private fun store() {
        val context = getApplication<Application>()
        try {
            context.startActivity(storeIntent("market://details?id=$GALAXY_WEARABLE_PACKAGE"))
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(storeIntent("https://play.google.com/store/apps/details?id=$GALAXY_WEARABLE_PACKAGE"))
            } catch (e2: ActivityNotFoundException) {
                // No store and no browser: the how-to still names the app.
            }
        }
    }

    private fun storeIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
