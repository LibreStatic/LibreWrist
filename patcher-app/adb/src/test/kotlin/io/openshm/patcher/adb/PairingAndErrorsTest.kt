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
        override fun pair(host: String, port: Int, code: String, abort: AbortScope) {
            pairCalls += Triple(host, port, code)
            onPair(host, port, code)
        }

        override fun connect(host: String, port: Int, timeoutMillis: Long, abort: AbortScope): AdbTransport =
            FakeTransport { FakeChannel() }
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

    @Test
    fun `a timed-out pairing closes the resources the backend registered`() = runBlocking {
        val latch = java.util.concurrent.CountDownLatch(1)
        var closed = false
        val backend = object : AdbBackend {
            override fun pair(host: String, port: Int, code: String, abort: AbortScope) {
                abort.register(java.io.Closeable { closed = true; latch.countDown() })
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }

            override fun connect(host: String, port: Int, timeoutMillis: Long, abort: AbortScope): AdbTransport = error("unused")
        }
        try {
            AdbPairing(backend, timeoutMillis = 150).pair("h", 1, "123456")
            fail()
        } catch (e: AdbException.Pairing) {
            assertEquals(PairingFailure.TIMEOUT, e.reason)
        }
        assertTrue(closed)
    }

    @Test
    fun `abort scope closes registered and late resources`() {
        val scope = AbortScope()
        var first = false
        var late = false
        scope.register(java.io.Closeable { first = true })
        scope.close()
        assertTrue(first)
        assertTrue(scope.isAborted)
        try {
            scope.register(java.io.Closeable { late = true })
            fail()
        } catch (e: IOException) {
        }
        assertTrue(late)
    }
}
