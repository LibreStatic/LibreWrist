package io.openshm.patcher.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStartTest {
    @Test
    fun watchCommandTargetsTheReceiverAndIncludesStoppedPackages() {
        assertEquals(
            "am broadcast -a io.example.shmbridge.START -n io.example.shmbridge.watch/.BridgeAlarmReceiver -f 32",
            BridgeStart.watchCommand("io.example.shmbridge.watch", "io.example"),
        )
        assertEquals(0x20, BridgeStart.INCLUDE_STOPPED)
    }

    @Test
    fun unsafeValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BridgeStart.watchCommand("io.example.x; reboot", "io.example") }
        assertThrows(IllegalArgumentException::class.java) { BridgeStart.watchCommand("io.example.x", "io example") }
        assertThrows(IllegalArgumentException::class.java) { BridgeStart.action("noDots") }
    }

    @Test
    fun onlyTheBridgesAreStarted() {
        assertTrue(BridgeStart.isBridge("bridge-watch"))
        assertTrue(BridgeStart.isBridge("bridge-phone"))
        assertFalse(BridgeStart.isBridge("alarm-fork"))
        assertFalse(BridgeStart.isBridge("shm-watch"))
    }
}
