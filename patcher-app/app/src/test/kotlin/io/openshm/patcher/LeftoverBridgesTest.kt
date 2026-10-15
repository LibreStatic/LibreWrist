package io.openshm.patcher

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.ui.screens.install.LeftoverBridges
import org.junit.Assert.assertEquals
import org.junit.Test

class LeftoverBridgesTest {
    private val ph = "\${PKG_PREFIX}"
    private val outputs = listOf(
        OutputApp("alarm-fork", "Alarm", Device.Watch, "$ph.shmbridge.alarm", null, singleCopy = true),
        OutputApp("bridge-watch", "Bridge", Device.Watch, "$ph.shmbridge.watch", null, singleCopy = true),
        OutputApp("bridge-phone", "Bridge", Device.Phone, "$ph.shmbridge.phone", null, singleCopy = true),
        OutputApp("shealth", "Health+", Device.Watch, "$ph.shealth", null),
        OutputApp("shealth-phone", "Health+", Device.Phone, "$ph.shealth", null, singleCopy = true),
        OutputApp("shm-watch", "SHM", Device.Watch, "com.samsung.android.shealthmonitors", null),
    )
    private val current = "io.openshm.ucc1840"

    private fun watch(vararg installed: String) = LeftoverBridges.find(outputs, Device.Watch, current, installed.toList())

    @Test
    fun findsBridgeCopiesOfOtherPrefixes() {
        assertEquals(
            listOf("io.openshm.u98ff48.shmbridge.alarm", "io.openshm.u98ff48.shmbridge.watch", "ar.facu.shmbridge.watch"),
            watch("io.openshm.u98ff48.shmbridge.watch", "io.openshm.u98ff48.shmbridge.alarm", "ar.facu.shmbridge.watch"),
        )
    }

    @Test
    fun ignoresTheCurrentPrefixSamsungHealthAndStockApps() {
        assertEquals(
            emptyList<String>(),
            watch(
                "$current.shmbridge.watch", "$current.shmbridge.alarm", "io.openshm.u98ff48.shealth",
                "com.sec.android.app.shealth", "com.samsung.android.shealthmonitors",
            ),
        )
    }

    private fun phone(vararg installed: String) = LeftoverBridges.find(outputs, Device.Phone, current, installed.toList())

    @Test
    fun findsASamsungHealthPlusCopyUnderAnEarlierPrefixOnThePhone() {
        assertEquals(
            listOf("io.openshm.u98ff48.shealth", "ar.facu.shealth"),
            phone("io.openshm.u98ff48.shealth", "ar.facu.shealth", "$current.shealth"),
        )
    }

    @Test
    fun theStockSamsungHealthPackageIsNeverALeftover() {
        assertEquals(emptyList<String>(), phone("com.sec.android.app.shealth", "com.samsung.android.app.shealth"))
        assertEquals(emptyList<String>(), phone("com.sec.android.app.shealth", "$current.shealth"))
    }

    @Test
    fun thePatchersOwnPackageIsNeverALeftover() {
        assertEquals(
            emptyList<String>(),
            LeftoverBridges.find(outputs, Device.Phone, current, listOf("io.openshm.patcher.shealth"), setOf("io.openshm.patcher.shealth")),
        )
    }

    @Test
    fun ignoresAnInvalidPrefix() {
        assertEquals(emptyList<String>(), watch("x.shmbridge.watch", "shmbridge.watch"))
    }

    @Test
    fun findsNothingWithoutAValidCurrentPrefix() {
        assertEquals(emptyList<String>(), LeftoverBridges.find(outputs, Device.Watch, "", listOf("io.openshm.u98ff48.shmbridge.watch")))
    }

    @Test
    fun ignoresTheOtherDevicesBridgeAndTheExcludedPackage() {
        assertEquals(emptyList<String>(), watch("io.openshm.u98ff48.shmbridge.phone"))
        assertEquals(listOf("io.openshm.u98ff48.shmbridge.phone"), LeftoverBridges.find(outputs, Device.Phone, current, listOf("io.openshm.u98ff48.shmbridge.phone")))
        assertEquals(emptyList<String>(), LeftoverBridges.find(outputs, Device.Watch, current, listOf("ar.facu.shmbridge.watch"), setOf("ar.facu.shmbridge.watch")))
    }
}
