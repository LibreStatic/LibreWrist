package io.openshm.patcher.ui.screens.wearable

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.engine.CompanionApp

/** Reads the companion app, its watch plugin and the bonded devices from the phone; changes nothing. */
internal fun readWearableFacts(context: Context, companion: CompanionApp): WearableFacts {
    // Debug builds with a fixed emulated watch (see AdbDiscovery.fixedServices): it cannot be paired
    // through Galaxy Wearable, so the pairing is reported as done.
    if (AdbDiscovery.fixedServices != null) {
        return WearableFacts(setOfNotNull(companion.packageName, companion.watchPlugins.firstOrNull()), true, listOf(BondedDevice("Debug watch", null)))
    }
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
    val installed = (listOf(companion.packageName) + companion.watchPlugins).filterTo(mutableSetOf()) { isInstalled(context, it) }
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
