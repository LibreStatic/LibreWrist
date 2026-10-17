package io.openshm.patcher

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.RegionSignals
import io.openshm.patcher.data.chinaShealthVersions
import io.openshm.patcher.data.detectChina
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionDetectorTest {
    private val none = RegionSignals(null, null, null, null, null)
    private val versions = setOf("7.00.6.012")

    @Test fun noSignalsIsNotChina() = assertFalse(detectChina(none, versions))

    @Test fun simAloneIsChina() = assertTrue(detectChina(none.copy(simCountryIso = "cn"), emptySet()))

    @Test fun networkAloneIsChina() = assertTrue(detectChina(none.copy(networkCountryIso = "cn"), emptySet()))

    @Test fun isoMatchIgnoresCase() {
        assertTrue(detectChina(none.copy(simCountryIso = "CN"), emptySet()))
        assertTrue(detectChina(none.copy(networkCountryIso = "Cn"), emptySet()))
    }

    @Test fun hongKongMacauAndTaiwanAreNotChina() {
        for (iso in listOf("hk", "mo", "tw")) {
            assertFalse(detectChina(none.copy(simCountryIso = iso, networkCountryIso = iso), versions))
        }
    }

    @Test fun blankStringsCountAsMissing() {
        assertFalse(detectChina(RegionSignals("", " ", "", "", ""), versions))
        assertFalse(detectChina(none.copy(stockShealthVersion = ""), setOf("")))
    }

    @Test fun chinaLocaleNeedsAMainlandTimeZone() {
        for (tz in listOf("Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Harbin")) {
            assertTrue(tz, detectChina(none.copy(localeRegion = "CN", timeZoneId = tz), emptySet()))
        }
        assertFalse(detectChina(none.copy(localeRegion = "CN", timeZoneId = "Europe/Berlin"), emptySet()))
        assertFalse(detectChina(none.copy(localeRegion = "CN"), emptySet()))
        assertFalse(detectChina(none.copy(localeRegion = "US", timeZoneId = "Asia/Shanghai"), emptySet()))
        assertFalse(detectChina(none.copy(localeRegion = "TW", timeZoneId = "Asia/Taipei"), emptySet()))
        assertFalse(detectChina(none.copy(timeZoneId = "Asia/Shanghai"), emptySet()))
    }

    @Test fun stockChinaBuildIsChina() {
        assertTrue(detectChina(none.copy(stockShealthVersion = "7.00.6.012"), versions))
        assertFalse(detectChina(none.copy(stockShealthVersion = "6.99.0.001"), versions))
        assertFalse(detectChina(none.copy(stockShealthVersion = "7.00.6.012"), emptySet()))
    }

    private fun input(id: String, pkg: String, version: String, onYyb: Boolean) =
        InputApp(id, id, version, Device.Phone, "0".repeat(64), pkg, "slug", emptyList(), onYyb = onYyb)

    @Test fun chinaVersionsAreTheShealthInputsOnYyb() {
        val inputs = listOf(
            input("a", "com.sec.android.app.shealth", "7.00.6.012", onYyb = false),
            input("b", "com.sec.android.app.shealth", "7.00.6.012.cn", onYyb = true),
            input("c", "com.other.app", "1.0", onYyb = true),
        )
        assertEquals(setOf("7.00.6.012.cn"), chinaShealthVersions(inputs))
        assertEquals(emptySet<String>(), chinaShealthVersions(inputs.take(1)))
        assertEquals(emptySet<String>(), chinaShealthVersions(emptyList()))
    }
}
