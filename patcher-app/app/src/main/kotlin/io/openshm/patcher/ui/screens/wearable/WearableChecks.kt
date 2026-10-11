package io.openshm.patcher.ui.screens.wearable

import io.openshm.patcher.R
import io.openshm.patcher.ui.components.CheckStatus

/** Samsung's official app that pairs and manages a Galaxy Watch. */
internal const val GALAXY_WEARABLE_PACKAGE = "com.samsung.android.app.watchmanager"

/** The watch plugin Galaxy Wearable installs for Galaxy Watch4 and newer. */
internal const val WATCH_PLUGIN_PACKAGE = "com.samsung.android.heartplugin"

/** `BluetoothClass.Device.Major.WEARABLE`, kept here so the check logic has no platform dependency. */
internal const val BLUETOOTH_MAJOR_WEARABLE = 0x0700

/**
 * Whether [packageName] is a Galaxy Wearable watch plugin: the known Watch4+ one, or any Samsung
 * `*plugin` package (the older watches' plugins follow that naming). Only packages this app can see
 * (declared in the manifest `<queries>`) are ever tested.
 */
internal fun isWatchPlugin(packageName: String): Boolean =
    packageName == WATCH_PLUGIN_PACKAGE ||
        (packageName.startsWith("com.samsung.android.") && packageName.endsWith("plugin"))

/** A bonded Bluetooth device, reduced to what the watch test needs. */
data class BondedDevice(val name: String?, val majorClass: Int?)

/** A bonded device counts as a watch when it is a wearable-class device or its name says "watch". */
internal fun BondedDevice.isWatch(): Boolean =
    majorClass == BLUETOOTH_MAJOR_WEARABLE || name?.contains("watch", ignoreCase = true) == true

/** What the phone reports, before it is turned into checks. */
data class WearableFacts(
    val installedPackages: Set<String> = emptySet(),
    val bluetoothAllowed: Boolean = false,
    val bonded: List<BondedDevice> = emptyList(),
)

/** What a check offers the user to do about it, besides installing or pairing in Galaxy Wearable. */
enum class WearableCheckAction { AllowBluetooth }

internal fun wearableChecks(facts: WearableFacts): List<WearableCheck> {
    val appInstalled = GALAXY_WEARABLE_PACKAGE in facts.installedPackages
    val pluginInstalled = facts.installedPackages.any(::isWatchPlugin)
    val watchBonded = facts.bluetoothAllowed && facts.bonded.any { it.isWatch() }
    return listOf(
        WearableCheck(
            "wearable-installed", R.string.wearable_check_gw,
            if (appInstalled) R.string.wearable_check_gw_ok else R.string.wearable_check_gw_missing,
            if (appInstalled) CheckStatus.Passed else CheckStatus.Failed,
        ),
        WearableCheck(
            "wearable-plugin", R.string.wearable_check_plugin,
            if (pluginInstalled) R.string.wearable_check_plugin_ok else R.string.wearable_check_plugin_missing,
            if (pluginInstalled) CheckStatus.Passed else CheckStatus.Failed,
        ),
        when {
            !facts.bluetoothAllowed -> WearableCheck(
                "watch-bonded", R.string.wearable_check_bonded, R.string.wearable_check_bonded_denied,
                CheckStatus.Warning, WearableCheckAction.AllowBluetooth,
            )
            watchBonded -> WearableCheck("watch-bonded", R.string.wearable_check_bonded, R.string.wearable_check_bonded_ok, CheckStatus.Passed)
            else -> WearableCheck("watch-bonded", R.string.wearable_check_bonded, R.string.wearable_check_bonded_missing, CheckStatus.Failed)
        },
    )
}

internal fun wearableState(facts: WearableFacts) = WearablePairingUiState(
    checks = wearableChecks(facts),
    galaxyWearableInstalled = GALAXY_WEARABLE_PACKAGE in facts.installedPackages,
)
