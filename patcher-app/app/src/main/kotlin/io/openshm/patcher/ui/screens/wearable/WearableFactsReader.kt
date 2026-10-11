package io.openshm.patcher.ui.screens.wearable

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Reads Galaxy Wearable, its watch plugin and the bonded devices from the phone; changes nothing. */
internal fun readWearableFacts(context: Context): WearableFacts {
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
    val installed = listOf(GALAXY_WEARABLE_PACKAGE, WATCH_PLUGIN_PACKAGE).filterTo(mutableSetOf()) { isInstalled(context, it) }
    return WearableFacts(installed, allowed, bonded)
}

@Suppress("DEPRECATION")
private fun isInstalled(context: Context, packageName: String): Boolean = try {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        pm.getPackageInfo(packageName, 0)
    }
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
}
