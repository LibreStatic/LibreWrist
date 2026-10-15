package io.openshm.patcher

import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.wearable.BondedDevice
import io.openshm.patcher.ui.screens.wearable.WearableCheckAction
import io.openshm.patcher.ui.screens.wearable.WearableFacts
import io.openshm.patcher.ui.screens.wearable.isWatch
import io.openshm.patcher.ui.screens.wearable.isWatchPlugin
import io.openshm.patcher.ui.screens.wearable.wearableState
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class WearableChecksTest {
    private val companion = TestCatalogue.load().platform.companionApp
    private val heartPlugin = "com.samsung.android.heartplugin"
    private val watch = BondedDevice("Galaxy Watch5 Pro (A1B2)", 0x0700)
    private val everything = WearableFacts(setOf(companion.packageName, heartPlugin), true, listOf(watch))

    private fun statuses(facts: WearableFacts) = wearableState(facts, companion).checks.map { it.status }

    @Test
    fun everythingPresentPassesAndOffersTheOpenAction() {
        val state = wearableState(everything, companion)
        assertTrue(state.allPassed)
        assertTrue(state.galaxyWearableInstalled)
        assertEquals(3, state.checks.size)
    }

    @Test
    fun eachCheckFailsOnItsOwn() {
        assertEquals(
            listOf(CheckStatus.Failed, CheckStatus.Passed, CheckStatus.Passed),
            statuses(everything.copy(installedPackages = setOf(heartPlugin))),
        )
        assertEquals(
            listOf(CheckStatus.Passed, CheckStatus.Failed, CheckStatus.Passed),
            statuses(everything.copy(installedPackages = setOf(companion.packageName))),
        )
        assertEquals(
            listOf(CheckStatus.Passed, CheckStatus.Passed, CheckStatus.Failed),
            statuses(everything.copy(bonded = listOf(BondedDevice("Headphones", 0x0400)))),
        )
        val none = wearableState(WearableFacts(bluetoothAllowed = true), companion)
        assertFalse(none.allPassed)
        assertFalse(none.galaxyWearableInstalled)
    }

    @Test
    fun deniedBluetoothIsAWarningWithAnAllowActionNeverAPass() {
        val state = wearableState(everything.copy(bluetoothAllowed = false), companion)
        val bonded = state.checks.last()
        assertEquals(CheckStatus.Warning, bonded.status)
        assertEquals(WearableCheckAction.AllowBluetooth, bonded.action)
        assertFalse(state.allPassed)
        // Even a leaked device list does not count without the permission.
        assertEquals(CheckStatus.Warning, statuses(everything.copy(bluetoothAllowed = false)).last())
        assertNull(wearableState(everything, companion).checks.last().action)
    }

    @Test
    fun aWatchIsAWearableClassDeviceOrOneNamedWatch() {
        assertTrue(BondedDevice(null, 0x0700).isWatch())
        assertTrue(BondedDevice("Galaxy Watch4 (X)", null).isWatch())
        assertTrue(BondedDevice("my WATCH", 0x0100).isWatch())
        assertFalse(BondedDevice("Buds Pro", 0x0400).isWatch())
        assertFalse(BondedDevice(null, null).isWatch())
    }

    @Test
    fun everyWearOsWatchFamilyHasItsPlugin() {
        assertTrue(isWatchPlugin("com.samsung.android.waterplugin", companion))
        assertTrue(isWatchPlugin("com.samsung.android.heartplugin", companion))
        assertTrue(isWatchPlugin("com.samsung.wearable.watch6plugin", companion))
        assertTrue(isWatchPlugin("com.samsung.wearable.watch7plugin", companion))
        assertTrue(isWatchPlugin("com.samsung.wearable.watchuniteplugin", companion))
        assertFalse(isWatchPlugin("com.samsung.android.gearoplugin", companion))
        assertFalse(isWatchPlugin("com.samsung.android.app.watchmanager", companion))
        assertFalse(isWatchPlugin("com.example.plugin", companion))
    }

    @Test
    fun aWatch8PluginPassesThePluginCheck() {
        val watch8 = everything.copy(installedPackages = setOf(companion.packageName, "com.samsung.wearable.watchuniteplugin"))
        assertTrue(wearableState(watch8, companion).allPassed)
    }
}
