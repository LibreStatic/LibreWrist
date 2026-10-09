package io.openshm.patcher.adb

import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryAndSelfAdbTest {
    private fun ip(vararg b: Int) = InetAddress.getByAddress(ByteArray(b.size) { b[it].toByte() })

    @Test
    fun `host picker prefers IPv4 and skips link-local IPv6`() {
        val v6ll = ip(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        val v6 = ip(0x20, 0x01, 0xd, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
        assertEquals("192.168.1.5", HostPicker.pick(listOf(v6ll, v6, ip(192, 168, 1, 5))))
        assertEquals("2001:db8:0:0:0:0:0:1", HostPicker.pick(listOf(v6ll, v6)))
        assertNull(HostPicker.pick(listOf(v6ll)))
        assertNull(HostPicker.pick(emptyList()))
    }

    @Test
    fun `registry keeps one entry per name, sorted, and removes lost services`() {
        val r = ServiceRegistry()
        r.put(AdbService("b", "1.1.1.1", 1, AdbServiceType.CONNECT))
        r.put(AdbService("a", "1.1.1.2", 2, AdbServiceType.CONNECT))
        r.put(AdbService("b", "1.1.1.3", 3, AdbServiceType.CONNECT))
        assertEquals(listOf("a" to 2, "b" to 3), r.snapshot().map { it.name to it.port })
        r.remove("a")
        assertEquals(listOf("b"), r.snapshot().map { it.name })
    }

    private class Env(val pairingCode: (String) -> Unit = {}) {
        val pairing = MutableStateFlow<List<AdbService>>(emptyList())
        val connect = MutableStateFlow<List<AdbService>>(emptyList())
        val pairCalls = mutableListOf<Triple<String, Int, String>>()
        val connectCalls = mutableListOf<Pair<String, Int>>()
        var failPairing: Throwable? = null
        var pairGate: java.util.concurrent.CountDownLatch? = null
        val backend = object : AdbBackend {
            override fun pair(host: String, port: Int, code: String, abort: AbortScope) {
                pairCalls += Triple(host, port, code)
                pairGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                failPairing?.let { throw it }
            }

            override fun connect(host: String, port: Int, timeoutMillis: Long, abort: AbortScope): AdbTransport {
                connectCalls += host to port
                return FakeTransport { FakeChannel() }
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val session = SelfAdbSession(
            backend, pairing, connect, scope, connectServiceTimeoutMillis = 300,
            isLocalHost = { it.startsWith("192.168.1.5") || it.startsWith("10.0.0.") },
        )
    }

    private suspend fun SelfAdbSession.await(predicate: (SelfAdbState) -> Boolean) =
        withTimeout(3_000) { state.first(predicate) }

    @Test
    fun `self-ADB pairs on loopback with the discovered port and connects`() = runBlocking {
        val env = Env()
        env.session.start()
        env.session.await { it is SelfAdbState.WaitingForPairingService }
        env.pairing.value = listOf(AdbService("adb-x", "192.168.1.5", 40001, AdbServiceType.PAIRING))
        env.session.await { it == SelfAdbState.WaitingForCode(40001) }

        env.connect.value = listOf(AdbService("adb-x", "192.168.1.5", 41111, AdbServiceType.CONNECT))
        env.session.submitCode(" 123456 ")
        val done = env.session.await { it is SelfAdbState.Connected }
        assertTrue((done as SelfAdbState.Connected).client.isConnected)
        assertEquals(listOf(Triple("127.0.0.1", 40001, "123456")), env.pairCalls)
        assertEquals(listOf("127.0.0.1" to 41111), env.connectCalls)
        env.scope.cancel()
    }

    @Test
    fun `wrong code returns to waiting for a code with the reason`() = runBlocking {
        val env = Env()
        env.failPairing = java.io.IOException("Exchanging message wasn't successful.")
        env.session.start()
        env.pairing.value = listOf(AdbService("adb-x", "192.168.1.5", 40002, AdbServiceType.PAIRING))
        env.session.await { it is SelfAdbState.WaitingForCode }
        env.session.submitCode("000000")
        env.session.await { it == SelfAdbState.WaitingForCode(40002, PairingFailure.WRONG_CODE) }
        env.scope.cancel()
    }

    @Test
    fun `codes are ignored until a pairing service is known, and a missing connect service fails`() = runBlocking {
        val env = Env()
        env.session.start()
        assertNull(env.session.submitCode("123456"))
        env.pairing.value = listOf(AdbService("adb-x", "192.168.1.5", 40003, AdbServiceType.PAIRING))
        env.session.await { it is SelfAdbState.WaitingForCode }
        env.session.submitCode("123456")
        val failed = env.session.await { it is SelfAdbState.Failed } as SelfAdbState.Failed
        assertTrue(failed.error is AdbException.Discovery)
        assertTrue(env.connectCalls.isEmpty())
        env.scope.cancel()
    }

    @Test
    fun `services advertised by other hosts are ignored`() = runBlocking {
        val env = Env()
        env.session.start()
        env.pairing.value = listOf(AdbService("watch", "192.168.1.77", 39999, AdbServiceType.PAIRING))
        Thread.sleep(150)
        assertEquals(SelfAdbState.WaitingForPairingService, env.session.state.value)
        env.pairing.value = listOf(
            AdbService("watch", "192.168.1.77", 39999, AdbServiceType.PAIRING),
            AdbService("me", "192.168.1.5", 40010, AdbServiceType.PAIRING),
        )
        env.session.await { it == SelfAdbState.WaitingForCode(40010) }

        // A remote connect service is not dialled either.
        env.connect.value = listOf(AdbService("watch", "192.168.1.77", 45555, AdbServiceType.CONNECT))
        env.session.submitCode("123456")
        val failed = env.session.await { it is SelfAdbState.Failed } as SelfAdbState.Failed
        assertTrue(failed.error is AdbException.Discovery)
        assertTrue(env.connectCalls.isEmpty())
        env.scope.cancel()
    }

    @Test
    fun `two local pairing services are ambiguous and recover when one disappears`() = runBlocking {
        val env = Env()
        env.session.start()
        val a = AdbService("a", "192.168.1.5", 40020, AdbServiceType.PAIRING)
        val b = AdbService("b", "10.0.0.2", 40021, AdbServiceType.PAIRING)
        env.pairing.value = listOf(a, b)
        env.session.await { it == SelfAdbState.AmbiguousService }
        assertNull(env.session.submitCode("123456"))
        assertTrue(env.pairCalls.isEmpty())
        env.pairing.value = listOf(b)
        env.session.await { it == SelfAdbState.WaitingForCode(40021) }
        env.scope.cancel()
    }

    @Test
    fun `two local connect services are ambiguous and nothing is dialled`() = runBlocking {
        val env = Env()
        env.session.start()
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40030, AdbServiceType.PAIRING))
        env.session.await { it is SelfAdbState.WaitingForCode }
        env.connect.value = listOf(
            AdbService("a", "192.168.1.5", 41001, AdbServiceType.CONNECT),
            AdbService("b", "10.0.0.2", 41002, AdbServiceType.CONNECT),
        )
        env.session.submitCode("123456")
        env.session.await { it == SelfAdbState.AmbiguousService }
        assertTrue(env.connectCalls.isEmpty())
        env.scope.cancel()
    }

    @Test
    fun `second submit while pairing is ignored and a stale result cannot overwrite the state`() = runBlocking {
        val env = Env()
        env.pairGate = java.util.concurrent.CountDownLatch(1)
        env.session.start()
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40040, AdbServiceType.PAIRING))
        env.session.await { it is SelfAdbState.WaitingForCode }
        assertTrue(env.session.submitCode("111111") != null)
        assertEquals(SelfAdbState.Pairing, env.session.state.value) // set synchronously
        assertNull(env.session.submitCode("222222"))

        // Restarting invalidates the in-flight pairing; its late completion must change nothing.
        env.connect.value = listOf(AdbService("c", "192.168.1.5", 41040, AdbServiceType.CONNECT))
        env.session.start()
        env.pairGate!!.countDown()
        Thread.sleep(300)
        assertTrue(env.session.state.value !is SelfAdbState.Connected)
        assertTrue(env.connectCalls.isEmpty())
        assertEquals(1, env.pairCalls.size)
        env.scope.cancel()
    }
}
