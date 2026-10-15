package io.openshm.patcher.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BridgeStartTest {
    @Test
    fun watchCommandTargetsTheReceiverAndIncludesStoppedPackages() {
        assertEquals(
            "am broadcast -a io.example.app.START -n io.example.app.watch/.StartReceiver -f 32",
            BridgeStart.watchCommand("io.example.app.watch", ".StartReceiver", "io.example.app.START"),
        )
        assertEquals(0x20, BridgeStart.INCLUDE_STOPPED)
    }

    @Test
    fun unsafeValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BridgeStart.watchCommand("io.example.x; reboot", ".R", "io.example.START") }
        assertThrows(IllegalArgumentException::class.java) { BridgeStart.watchCommand("io.example.x", ".R", "io example.START") }
        assertThrows(IllegalArgumentException::class.java) { BridgeStart.watchCommand("io.example.x", ".R; reboot", "io.example.START") }
        assertThrows(IllegalArgumentException::class.java) { BridgeStart.requireAction("noDots") }
    }
}
