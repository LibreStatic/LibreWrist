package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbChannel
import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbTransport
import io.openshm.patcher.adb.PairingFailure
import io.openshm.patcher.adb.SelfAdbState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnAfterPairingTest {
    private val connected = SelfAdbState.Connected(AdbClient(UnusedTransport))

    private fun ReturnAfterPairing.feed(vararg states: SelfAdbState): List<Boolean> = states.map { onState(it) }

    @Test
    fun `a stored pairing that reconnects does not bring the app forward`() {
        val results = ReturnAfterPairing().feed(SelfAdbState.Reconnecting, SelfAdbState.Connecting, connected)
        assertEquals(listOf(false, false, false), results)
    }

    @Test
    fun `pairing with a code brings the app forward once connected`() {
        val results = ReturnAfterPairing().feed(
            SelfAdbState.Reconnecting,
            SelfAdbState.WaitingForPairingService,
            SelfAdbState.WaitingForCode(port = 37000),
            SelfAdbState.Pairing,
            SelfAdbState.WaitingForConnectService,
            SelfAdbState.Connecting,
            connected,
        )
        assertEquals(listOf(false, false, false, false, false, false, true), results)
    }

    @Test
    fun `a wrong code first still brings the app forward`() {
        val tracker = ReturnAfterPairing()
        tracker.feed(
            SelfAdbState.WaitingForCode(port = 37000),
            SelfAdbState.Pairing,
            SelfAdbState.WaitingForCode(port = 37000, lastFailure = PairingFailure.WRONG_CODE),
            SelfAdbState.Pairing,
        )
        assertTrue(tracker.onState(connected))
    }

    @Test
    fun `turning Wireless debugging on with a stored pairing brings the app forward`() {
        // The pairing screen was shown, but the stored pairing connected once the switch was on.
        val results = ReturnAfterPairing().feed(SelfAdbState.WaitingForPairingService, SelfAdbState.Connecting, connected)
        assertEquals(listOf(false, false, true), results)
    }

    @Test
    fun `it fires once per visit to the pairing screen`() {
        val tracker = ReturnAfterPairing()
        tracker.feed(SelfAdbState.WaitingForPairingService)
        assertTrue(tracker.onState(connected))
        assertFalse(tracker.onState(connected))
    }

    @Test
    fun `a failed attempt does not bring the app forward`() {
        val results = ReturnAfterPairing().feed(
            SelfAdbState.WaitingForPairingService,
            SelfAdbState.Failed(AdbException.Notifications("off")),
        )
        assertEquals(listOf(false, false), results)
    }

    @Test
    fun `the command starts the launcher activity of the app`() {
        assertEquals(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n io.openshm.patcher/io.openshm.patcher.MainActivity",
            ReturnAfterPairing.command("io.openshm.patcher", "io.openshm.patcher.MainActivity"),
        )
    }
}

/** The tracker never touches the connection; opening a stream on it is a test bug. */
private object UnusedTransport : AdbTransport {
    override val isConnected = true
    override fun open(destination: String): AdbChannel = error("unexpected stream $destination")
    override fun close() {}
}
