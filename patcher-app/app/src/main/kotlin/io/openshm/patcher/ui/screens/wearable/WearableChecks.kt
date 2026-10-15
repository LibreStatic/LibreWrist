package io.openshm.patcher.ui.screens.wearable

import io.openshm.patcher.R
import io.openshm.patcher.engine.CompanionApp
import io.openshm.patcher.ui.components.CheckStatus

/** `BluetoothClass.Device.Major.WEARABLE`, kept here so the check logic has no platform dependency. */
internal const val BLUETOOTH_MAJOR_WEARABLE = 0x0700

/**
 * Whether [packageName] is a watch plugin of the [companion] app (catalogue `platform.companionApp`).
 * Every model family has its own plugin, and all of them must also be listed in the manifest `<queries>`,
 * or this app cannot see them.
 */
internal fun isWatchPlugin(packageName: String, companion: CompanionApp): Boolean = packageName in companion.watchPlugins

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

internal fun wearableChecks(facts: WearableFacts, companion: CompanionApp): List<WearableCheck> {
    val appInstalled = companion.packageName in facts.installedPackages
    val pluginInstalled = facts.installedPackages.any { isWatchPlugin(it, companion) }
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

internal fun wearableState(facts: WearableFacts, companion: CompanionApp) = WearablePairingUiState(
    checks = wearableChecks(facts, companion),
    galaxyWearableInstalled = companion.packageName in facts.installedPackages,
)
