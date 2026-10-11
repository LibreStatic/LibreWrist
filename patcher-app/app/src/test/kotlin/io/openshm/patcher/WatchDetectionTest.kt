package io.openshm.patcher

import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.WatchDetector
import io.openshm.patcher.ui.screens.profile.WatchModelHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchDetectionTest {
    @Test
    fun androidFourteenIsWearOsFive() {
        val d = WatchDetector.parse("34\n14\n")!!
        assertEquals(34, d.sdk)
        assertEquals("14", d.release)
        assertEquals(WatchProfile.WearOs5, d.profile)
    }

    @Test
    fun androidThirteenAndElevenAreTheOlderProfile() {
        assertEquals(WatchProfile.WearOs3Or4, WatchDetector.parse("33\n13")!!.profile)
        assertEquals(WatchProfile.WearOs3Or4, WatchDetector.parse("30\n11")!!.profile)
    }

    @Test
    fun newerAndroidStillQualifies() {
        assertEquals(WatchProfile.WearOs5, WatchDetector.parse("36\n16")!!.profile)
    }

    @Test
    fun missingReleaseIsTolerated() {
        val d = WatchDetector.parse("34")!!
        assertNull(d.release)
        assertEquals("API 34", d.versionLabel)
    }

    @Test
    fun garbageIsNotAnAnswer() {
        assertNull(WatchDetector.parse(""))
        assertNull(WatchDetector.parse("getprop: not found"))
        assertNull(WatchDetector.parse("0\n0"))
        assertNull(WatchDetector.parse("-1"))
    }

    @Test
    fun noCorrectionWhenDetectionFailedOrAgrees() {
        assertNull(WatchDetector.correction(WatchProfile.WearOs5, null))
        assertNull(WatchDetector.correction(WatchProfile.WearOs5, DetectedWatch(34, "14")))
        assertNull(WatchDetector.correction(WatchProfile.WearOs3Or4, DetectedWatch(33, "13")))
    }

    @Test
    fun correctionWhenTheWatchDisagrees() {
        assertEquals(DetectedWatch(33, "13"), WatchDetector.correction(WatchProfile.WearOs5, DetectedWatch(33, "13")))
        assertEquals(DetectedWatch(34, "14"), WatchDetector.correction(WatchProfile.WearOs3Or4, DetectedWatch(34, "14")))
    }

    @Test
    fun newGalaxyWatchesAreCertain() {
        listOf("Galaxy Watch7 (A1B2)", "Galaxy Watch8 Classic (XXXX)", "Galaxy Watch Ultra (XXXX)", "Galaxy Watch 7", "GALAXY WATCH7")
            .forEach { assertEquals(it, WatchModelHint.Certain(it), WatchModelHint.fromBluetoothName(it)) }
    }

    @Test
    fun olderGalaxyWatchesDepend() {
        listOf("Galaxy Watch4 (1234)", "Galaxy Watch5 Pro (1234)", "Galaxy Watch6 Classic (1234)", "Galaxy Watch FE (1234)", "galaxy watch fe")
            .forEach { assertEquals(it, WatchModelHint.Depends(it), WatchModelHint.fromBluetoothName(it)) }
    }

    @Test
    fun otherDevicesAreIgnored() {
        listOf("Pixel Buds Pro", "Galaxy Buds2", "Galaxy Watch Active2 (1234)", "Galaxy Watch3 (1234)", "Galaxy Watch (1234)", "Pixel Watch 3", "")
            .forEach { assertNull(it, WatchModelHint.fromBluetoothName(it)) }
    }

    @Test
    fun bestPrefersACertainWatch() {
        val names = listOf("Car", "Galaxy Watch6 (1)", "Galaxy Watch7 (2)", "Galaxy Watch Ultra (3)")
        assertEquals(WatchModelHint.Certain("Galaxy Watch7 (2)"), WatchModelHint.best(names))
        assertEquals(WatchModelHint.Depends("Galaxy Watch6 (1)"), WatchModelHint.best(listOf("Car", "Galaxy Watch6 (1)")))
        assertNull(WatchModelHint.best(listOf("Car")))
    }
}
