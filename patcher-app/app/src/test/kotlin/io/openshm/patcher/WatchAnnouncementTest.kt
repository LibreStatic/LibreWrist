package io.openshm.patcher

import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.WatchAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchAnnouncementTest {
    private fun txt(name: String?, api: String?) = buildMap {
        name?.let { put("name", it) }
        api?.let { put("api", it) }
        put("v", "1")
    }

    private fun service(name: String, attributes: Map<String, String>) =
        AdbService(name, "192.168.1.9", 5555, AdbServiceType.CONNECT, attributes)

    @Test
    fun watchOnApi36IsWearOsFive() {
        val d = WatchAnnouncement.parse(txt("SM-R920", "36"))!!
        assertEquals(36, d.sdk)
        assertNull(d.release)
        assertEquals("SM-R920", d.model)
        assertEquals(DetectedWatch.Source.Network, d.source)
        assertEquals(WatchProfile.WearOs5, d.profile)
    }

    @Test
    fun watchOnApi33IsTheOlderProfile() {
        assertEquals(WatchProfile.WearOs3Or4, WatchAnnouncement.parse(txt("SM-R870", "33"))!!.profile)
        assertEquals(WatchProfile.WearOs3Or4, WatchAnnouncement.parse(txt("sm-l300", "30"))!!.profile)
    }

    @Test
    fun fractionalApiLevelUsesTheIntegerPart() {
        assertEquals(36, WatchAnnouncement.parse(txt("SM-R920", "36.0"))!!.sdk)
    }

    @Test
    fun missingApiIsUnknown() {
        assertNull(WatchAnnouncement.parse(txt("SM-R920", null)))
        assertNull(WatchAnnouncement.parse(mapOf("v" to "1")))
    }

    @Test
    fun phonesAreIgnored() {
        assertNull(WatchAnnouncement.parse(txt("motorola razr fold", "36.0")))
        assertNull(WatchAnnouncement.parse(txt("SM-S928B", "35")))
        assertNull(WatchAnnouncement.parse(txt(null, "36")))
    }

    @Test
    fun garbageApiIsNotAnAnswer() {
        assertNull(WatchAnnouncement.parse(txt("SM-R920", "")))
        assertNull(WatchAnnouncement.parse(txt("SM-R920", "abc")))
        assertNull(WatchAnnouncement.parse(txt("SM-R920", "0")))
        assertNull(WatchAnnouncement.parse(txt("SM-R920", "-3")))
    }

    @Test
    fun bestSkipsPhonesAndTakesTheFirstWatch() {
        val services = listOf(
            service("a-phone", txt("motorola razr fold", "36.0")),
            service("b-old", txt(null, null)),
            service("c-watch", txt("SM-R920", "36")),
            service("d-watch", txt("SM-R870", "33")),
        )
        assertEquals("SM-R920", WatchAnnouncement.best(services)!!.model)
        assertNull(WatchAnnouncement.best(services.take(2)))
        assertNull(WatchAnnouncement.best(emptyList()))
    }
}
