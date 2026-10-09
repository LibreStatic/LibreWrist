package io.openshm.patcher.adb

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingAndErrorsTest {
    private class FakeBackend(val onPair: (String, Int, String) -> Unit = { _, _, _ -> }) : AdbBackend {
        val pairCalls = mutableListOf<Triple<String, Int, String>>()
        override fun pair(host: String, port: Int, code: String) {
            pairCalls += Triple(host, port, code)
            onPair(host, port, code)
        }

        override fun connect(host: String, port: Int, timeoutMillis: Long): AdbTransport = FakeTransport { FakeChannel() }
    }

    private fun reason(t: Throwable) = (AdbErrors.mapPairing(t) as AdbException.Pairing).reason

    @Test
    fun `pairing error mapping`() {
        assertEquals(PairingFailure.WRONG_CODE, reason(IOException("Exchanging message wasn't successful.")))
        assertEquals(PairingFailure.WRONG_CODE, reason(IOException("Could not exchange peer info.")))
        assertEquals(PairingFailure.WRONG_CODE, reason(IOException("wrapped", IOException("Exchanging message wasn't successful."))))
        assertEquals(PairingFailure.TIMEOUT, reason(SocketTimeoutException("read timed out")))
        assertEquals(PairingFailure.NETWORK, reason(ConnectException("refused")))
        assertEquals(PairingFailure.NETWORK, reason(SSLHandshakeException("bad")))
        assertEquals(PairingFailure.UNKNOWN, reason(IllegalStateException("?")))
    }

    @Test
    fun `connect error mapping by class name keeps libadb out of the mapper`() {
        class AdbPairingRequiredException : Exception("x")
        class AdbAuthenticationFailedException : RuntimeException("x")
        assertTrue(AdbErrors.mapConnect(AdbPairingRequiredException()) is AdbException.PairingRequired)
        assertTrue(AdbErrors.mapConnect(AdbAuthenticationFailedException()) is AdbException.AuthenticationRejected)
        assertTrue(AdbErrors.mapConnect(SocketTimeoutException()) is AdbException.Timeout)
        assertTrue(AdbErrors.mapConnect(IOException("x")) is AdbException.ConnectionFailed)
        val typed = AdbException.NotConnected()
        assertEquals(typed, AdbErrors.mapConnect(typed))
    }

    @Test
    fun `code format`() {
        assertTrue(AdbPairing.isValidCode("012345"))
        for (bad in listOf("12345", "1234567", "12345a", " 12345", "", "１２３４５６")) assertFalse(bad, AdbPairing.isValidCode(bad))
    }

    @Test
    fun `invalid code never reaches the backend`() = runBlocking {
        val backend = FakeBackend()
        try {
            AdbPairing(backend).pair("h", 1, "12ab56")
            fail()
        } catch (e: AdbException.Pairing) {
            assertEquals(PairingFailure.INVALID_CODE_FORMAT, e.reason)
            assertTrue(backend.pairCalls.isEmpty())
        }
    }

    @Test
    fun `successful pairing passes host port and code`() = runBlocking {
        val backend = FakeBackend()
        AdbPairing(backend).pair("192.168.1.9", 37123, "123456")
        assertEquals(listOf(Triple("192.168.1.9", 37123, "123456")), backend.pairCalls)
    }

    @Test
    fun `wrong code and network errors are typed`() = runBlocking {
        val wrong = FakeBackend { _, _, _ -> throw IOException("Exchanging message wasn't successful.") }
        try {
            AdbPairing(wrong).pair("h", 1, "123456")
            fail()
        } catch (e: AdbException.Pairing) {
            assertEquals(PairingFailure.WRONG_CODE, e.reason)
        }
        val net = FakeBackend { _, _, _ -> throw ConnectException("no route") }
        try {
            AdbPairing(net).pair("h", 1, "123456")
            fail()
        } catch (e: AdbException.Pairing) {
            assertEquals(PairingFailure.NETWORK, e.reason)
        }
    }

    @Test
    fun `a stuck pairing times out`() = runBlocking {
        val latch = java.util.concurrent.CountDownLatch(1)
        val stuck = FakeBackend { _, _, _ -> latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            AdbPairing(stuck, timeoutMillis = 150).pair("h", 1, "123456")
            fail()
        } catch (e: AdbException.Pairing) {
            assertEquals(PairingFailure.TIMEOUT, e.reason)
        } finally {
            latch.countDown()
        }
    }
}
