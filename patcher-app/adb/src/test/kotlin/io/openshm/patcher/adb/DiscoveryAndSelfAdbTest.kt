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

    @Test
    fun `TXT records decode as UTF-8 and empty or missing values become empty strings`() {
        val decoded = TxtRecords.decode(
            mapOf("api" to "36".toByteArray(), "name" to "SM-R920".toByteArray(), "flag" to null, "empty" to ByteArray(0)),
        )
        assertEquals(mapOf("api" to "36", "name" to "SM-R920", "flag" to "", "empty" to ""), decoded)
        assertEquals(emptyMap<String, String>(), TxtRecords.decode(null))
        assertEquals(emptyMap<String, String>(), AdbService("a", "1.1.1.1", 1, AdbServiceType.CONNECT).attributes)
    }

    private class Env(val pairingCode: (String) -> Unit = {}) {
        val pairing = MutableStateFlow<List<AdbService>>(emptyList())
        val connect = MutableStateFlow<List<AdbService>>(emptyList())
        val pairCalls: MutableList<Triple<String, Int, String>> = java.util.Collections.synchronizedList(mutableListOf())
        val connectCalls = mutableListOf<Pair<String, Int>>()
        var failPairing: Throwable? = null
        var failConnect: Throwable? = null
        val refusedPorts: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** Ports that refuse only the loopback address (adbd listening on the Wi-Fi address only). */
        val refusedOnLoopback: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())
        val logLines: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        var pairGate: java.util.concurrent.CountDownLatch? = null
        var connectGate: java.util.concurrent.CountDownLatch? = null
        var transports: MutableList<FakeTransport>? = null
        val failPorts: MutableMap<Int, Throwable> = java.util.concurrent.ConcurrentHashMap()
        val backend = object : AdbBackend {
            override fun pair(host: String, port: Int, code: String, abort: AbortScope) {
                pairCalls += Triple(host, port, code)
                pairGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                failPairing?.let { throw it }
            }

            override fun connect(host: String, port: Int, timeoutMillis: Long, abort: AbortScope): AdbTransport {
                connectCalls += host to port
                failConnect?.let { throw it }
                failPorts[port]?.let { throw it }
                connectGate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                if (port in refusedPorts || (host == "127.0.0.1" && port in refusedOnLoopback)) throw java.net.ConnectException("ECONNREFUSED")
                return FakeTransport { FakeChannel() }.also { transports?.add(it) }
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        val session = SelfAdbSession(
            backend, pairing, connect, scope, connectServiceTimeoutMillis = 300, reconnectTimeoutMillis = 150,
            isLocalHost = { it.startsWith("192.168.1.5") || it.startsWith("10.0.0.") },
            log = { logLines += it },
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
        env.session.await { it == SelfAdbState.WaitingForPairingService }
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
    fun `a stale local connect announcement is skipped for the live one`() = runBlocking {
        val env = Env()
        env.refusedPorts += 41001
        env.session.start()
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40030, AdbServiceType.PAIRING))
        env.session.await { it is SelfAdbState.WaitingForCode }
        // As seen on a real phone: the old port first, the live "(2)" copy a moment later.
        env.connect.value = listOf(AdbService("adb-x", "192.168.1.5", 41001, AdbServiceType.CONNECT))
        env.session.submitCode("123456")
        env.session.await { it == SelfAdbState.WaitingForConnectService && env.connectCalls.isNotEmpty() }
        env.connect.value = listOf(
            AdbService("adb-x", "192.168.1.5", 41001, AdbServiceType.CONNECT),
            AdbService("adb-x (2)", "192.168.1.5", 41002, AdbServiceType.CONNECT),
            AdbService("watch", "192.168.1.77", 45555, AdbServiceType.CONNECT),
        )
        env.session.await { it is SelfAdbState.Connected }
        assertEquals(listOf("127.0.0.1" to 41001, "192.168.1.5" to 41001, "127.0.0.1" to 41002), env.connectCalls)
        env.scope.cancel()
    }

    @Test
    fun `only refused local ports means a connection failure after the timeout`() = runBlocking {
        val env = Env()
        env.refusedPorts += listOf(41003, 41004)
        env.session.start()
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40031, AdbServiceType.PAIRING))
        env.session.await { it is SelfAdbState.WaitingForCode }
        env.connect.value = listOf(
            AdbService("a", "192.168.1.5", 41003, AdbServiceType.CONNECT),
            AdbService("b", "10.0.0.2", 41004, AdbServiceType.CONNECT),
        )
        env.session.submitCode("123456")
        val failed = env.session.await { it is SelfAdbState.Failed } as SelfAdbState.Failed
        assertTrue(failed.error is AdbException.ConnectionFailed)
        assertEquals(
            listOf("127.0.0.1" to 41003, "192.168.1.5" to 41003, "127.0.0.1" to 41004, "10.0.0.2" to 41004),
            env.connectCalls,
        )
        env.scope.cancel()
    }

    @Test
    fun `a connect error other than a refusal is final`() = runBlocking {
        val env = Env()
        env.session.start()
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40032, AdbServiceType.PAIRING))
        env.session.await { it is SelfAdbState.WaitingForCode }
        env.failConnect = javax.net.ssl.SSLException("handshake")
        env.session.submitCode("123456")
        env.connect.value = listOf(
            AdbService("a", "192.168.1.5", 41005, AdbServiceType.CONNECT),
            AdbService("b", "192.168.1.5", 41006, AdbServiceType.CONNECT),
        )
        val failed = env.session.await { it is SelfAdbState.Failed } as SelfAdbState.Failed
        assertTrue(failed.error is AdbException.ConnectionFailed)
        assertEquals(1, env.connectCalls.size)
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
        // Let the pairing reach the (gated) backend before restarting, so it is truly in flight.
        val deadline = System.nanoTime() + 5_000_000_000
        while (env.pairCalls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)

        // Restarting invalidates the in-flight pairing; its late completion must change nothing.
        env.session.start()
        env.pairGate!!.countDown()
        Thread.sleep(300) // the restarted session found no connect service to reconnect to
        env.refusedPorts += 41040 // the pairing-wait follower dials it, but the stored key is not usable
        env.connect.value = listOf(AdbService("c", "192.168.1.5", 41040, AdbServiceType.CONNECT))
        Thread.sleep(100)
        assertTrue(env.session.state.value is SelfAdbState.WaitingForCode || env.session.state.value == SelfAdbState.WaitingForPairingService)
        assertEquals(1, env.pairCalls.size)
        env.scope.cancel()
    }

    @Test
    fun `an already paired key reconnects without pairing`() = runBlocking {
        val env = Env()
        env.connect.value = listOf(
            AdbService("watch", "192.168.1.77", 45555, AdbServiceType.CONNECT),
            AdbService("me", "192.168.1.5", 41100, AdbServiceType.CONNECT),
        )
        env.session.start()
        val done = env.session.await { it is SelfAdbState.Connected || it is SelfAdbState.WaitingForPairingService }
        assertTrue(done is SelfAdbState.Connected && done.client.isConnected)
        assertEquals(listOf("127.0.0.1" to 41100), env.connectCalls)
        assertTrue(env.pairCalls.isEmpty())
        env.scope.cancel()
    }

    @Test
    fun `a stale refused port is skipped while reconnecting`() = runBlocking {
        val env = Env()
        env.refusedPorts += 41101
        env.connect.value = listOf(
            AdbService("a", "192.168.1.5", 41101, AdbServiceType.CONNECT),
            AdbService("b", "192.168.1.5", 41102, AdbServiceType.CONNECT),
        )
        env.session.start()
        env.session.await { it is SelfAdbState.Connected }
        assertEquals(listOf("127.0.0.1" to 41101, "192.168.1.5" to 41101, "127.0.0.1" to 41102), env.connectCalls)
        env.scope.cancel()
    }

    @Test
    fun `a rejected key falls back to the pairing flow`() = runBlocking {
        val env = Env()
        env.failConnect = javax.net.ssl.SSLException("certificate unknown")
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41103, AdbServiceType.CONNECT))
        env.session.start()
        env.session.await { it == SelfAdbState.WaitingForPairingService }
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40040, AdbServiceType.PAIRING))
        env.session.await { it == SelfAdbState.WaitingForCode(40040) }
        assertEquals(1, env.connectCalls.size)
        env.scope.cancel()
    }

    @Test
    fun `no local connect service falls back to the pairing flow`() = runBlocking {
        val env = Env()
        env.connect.value = listOf(AdbService("watch", "192.168.1.77", 45555, AdbServiceType.CONNECT))
        env.session.start()
        assertEquals(SelfAdbState.Reconnecting, env.session.state.value)
        env.session.await { it == SelfAdbState.WaitingForPairingService }
        assertTrue(env.connectCalls.isEmpty())
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40041, AdbServiceType.PAIRING))
        env.session.await { it == SelfAdbState.WaitingForCode(40041) }
        env.scope.cancel()
    }

    @Test
    fun `stop during the reconnect attempt leaves the session idle`() = runBlocking {
        val env = Env()
        env.session.start()
        assertEquals(SelfAdbState.Reconnecting, env.session.state.value)
        env.session.stop()
        Thread.sleep(300)
        assertEquals(SelfAdbState.Idle, env.session.state.value)
        env.scope.cancel()
    }

    @Test
    fun `a connect port that refuses loopback is dialled on the announced address`() = runBlocking {
        val env = Env()
        env.refusedOnLoopback += 41200
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41200, AdbServiceType.CONNECT))
        env.session.start()
        env.session.await { it is SelfAdbState.Connected }
        assertEquals(listOf("127.0.0.1" to 41200, "192.168.1.5" to 41200), env.connectCalls)
        env.scope.cancel()
    }

    @Test
    fun `an unexpected discovery error ends the session as failed instead of crashing`() = runBlocking {
        val env = Env()
        val broken = SelfAdbSession(
            env.backend,
            kotlinx.coroutines.flow.flow { throw SecurityException("no permission") },
            env.connect,
            env.scope,
            reconnectTimeoutMillis = 50,
            isLocalHost = { true },
            log = { env.logLines += it },
        )
        broken.start()
        val failed = broken.await { it is SelfAdbState.Failed } as SelfAdbState.Failed
        assertTrue(failed.error is AdbException.Discovery)
        assertTrue(env.logLines.any { it.contains("SecurityException") })
        env.scope.cancel()
    }

    @Test
    fun `diagnostic lines name the states and counts but never carry the code`() = runBlocking {
        val env = Env()
        env.session.start()
        env.session.await { it == SelfAdbState.WaitingForPairingService }
        env.pairing.value = listOf(
            AdbService("me", "192.168.1.5", 40300, AdbServiceType.PAIRING),
            AdbService("watch", "192.168.1.77", 40301, AdbServiceType.PAIRING),
        )
        env.session.await { it is SelfAdbState.WaitingForCode }
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41300, AdbServiceType.CONNECT))
        env.session.submitCode("987654")
        env.session.await { it is SelfAdbState.Connected }
        val lines = env.logLines.toList()
        assertTrue(lines.toString(), lines.contains("state Reconnecting"))
        assertTrue(lines.toString(), lines.contains("state WaitingForCode"))
        assertTrue(lines.toString(), lines.contains("pairing services: 2 found, 1 on this device"))
        assertTrue(lines.none { it.contains("987654") })
        env.scope.cancel()
    }

    private class MemoryStore(var endpoint: Pair<String, Int>? = null) : SelfAdbEndpointStore {
        override fun load() = endpoint

        override fun save(host: String, port: Int) {
            endpoint = host to port
        }
    }

    private fun Env.sessionWith(store: SelfAdbEndpointStore?, reconnectTimeout: Long = 150) = SelfAdbSession(
        backend, pairing, connect, scope, connectServiceTimeoutMillis = 300, reconnectTimeoutMillis = reconnectTimeout,
        isLocalHost = { it.startsWith("192.168.1.5") || it.startsWith("10.0.0.") || it == "127.0.0.1" },
        log = { logLines += it }, endpointStore = store, storedEndpointTimeoutMillis = 500,
    )

    @Test
    fun `a stored endpoint connects without consulting mDNS`() = runBlocking {
        val env = Env()
        val session = env.sessionWith(MemoryStore("192.168.1.5" to 42000), reconnectTimeout = 5_000)
        session.start()
        session.await { it is SelfAdbState.Connected }
        assertEquals(listOf("192.168.1.5" to 42000), env.connectCalls)
        env.scope.cancel()
    }

    @Test
    fun `a stored endpoint on a foreign host is not dialled`() = runBlocking {
        val env = Env()
        val session = env.sessionWith(MemoryStore("192.168.1.77" to 42000))
        session.start()
        session.await { it == SelfAdbState.WaitingForPairingService }
        assertTrue(env.connectCalls.isEmpty())
        env.scope.cancel()
    }

    @Test
    fun `a failing stored endpoint falls back to mDNS and is not reported as a rejected key`() {
        val failures = listOf<(Env) -> Unit>(
            { it.refusedPorts += 42001 },
            { it.failConnectFor(42001, javax.net.ssl.SSLException("certificate unknown")) },
            { it.failConnectFor(42001, java.net.SocketTimeoutException("timed out")) },
        )
        for (arrange in failures) runBlocking {
            val env = Env()
            arrange(env)
            env.connect.value = listOf(AdbService("me", "192.168.1.5", 41300, AdbServiceType.CONNECT))
            val store = MemoryStore("127.0.0.1" to 42001)
            val session = env.sessionWith(store)
            session.start()
            session.await { it is SelfAdbState.Connected }
            assertEquals("127.0.0.1" to 42001, env.connectCalls.first())
            assertEquals("127.0.0.1" to 41300, env.connectCalls.last())
            assertEquals(2, env.connectCalls.size)
            assertTrue(env.logLines.none { it.startsWith("reconnect failed") })
            assertEquals("127.0.0.1" to 41300, store.endpoint)
            env.scope.cancel()
        }
    }

    private fun Env.failConnectFor(port: Int, error: Throwable) {
        failPorts[port] = error
    }

    @Test
    fun `a successful connect saves the endpoint actually used, including the announced address`() = runBlocking {
        val env = Env()
        env.refusedOnLoopback += 41400
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41400, AdbServiceType.CONNECT))
        val store = MemoryStore()
        val session = env.sessionWith(store)
        session.start()
        session.await { it is SelfAdbState.Connected }
        assertEquals("192.168.1.5" to 41400, store.endpoint)
        env.scope.cancel()
    }

    @Test
    fun `pairing path saves the endpoint too`() = runBlocking {
        val env = Env()
        val store = MemoryStore()
        val session = env.sessionWith(store)
        session.start()
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40500, AdbServiceType.PAIRING))
        session.await { it is SelfAdbState.WaitingForCode }
        session.submitCode("123456")
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41500, AdbServiceType.CONNECT))
        session.await { it is SelfAdbState.Connected }
        assertEquals("127.0.0.1" to 41500, store.endpoint)
        env.scope.cancel()
    }

    @Test
    fun `a connect service that appears while waiting for pairing connects with the stored key`() = runBlocking {
        val env = Env()
        val session = env.sessionWith(null)
        session.start()
        session.await { it == SelfAdbState.WaitingForPairingService }
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40600, AdbServiceType.PAIRING))
        session.await { it is SelfAdbState.WaitingForCode }
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41600, AdbServiceType.CONNECT))
        session.await { it is SelfAdbState.Connected }
        assertTrue(env.pairCalls.isEmpty())
        env.scope.cancel()
    }

    @Test
    fun `a connect service that rejects the key leaves the pairing state`() = runBlocking {
        val env = Env()
        val session = env.sessionWith(null)
        session.start()
        session.await { it == SelfAdbState.WaitingForPairingService }
        env.failConnect = javax.net.ssl.SSLException("certificate unknown")
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41700, AdbServiceType.CONNECT))
        val deadline = System.nanoTime() + 3_000_000_000
        while (env.connectCalls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        Thread.sleep(150)
        assertEquals(SelfAdbState.WaitingForPairingService, session.state.value)
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40700, AdbServiceType.PAIRING))
        session.await { it == SelfAdbState.WaitingForCode(40700) }
        assertEquals(1, env.connectCalls.size)
        env.scope.cancel()
    }

    @Test
    fun `a code submitted while a follower dial is pending closes the stale result`() = runBlocking {
        val env = Env()
        val session = env.sessionWith(null)
        session.start()
        env.pairing.value = listOf(AdbService("p", "192.168.1.5", 40800, AdbServiceType.PAIRING))
        session.await { it is SelfAdbState.WaitingForCode }
        val gate = java.util.concurrent.CountDownLatch(1)
        val transports = java.util.Collections.synchronizedList(mutableListOf<FakeTransport>())
        env.connectGate = gate
        env.transports = transports
        env.pairGate = java.util.concurrent.CountDownLatch(1) // keep the submitted code in flight
        env.connect.value = listOf(AdbService("me", "192.168.1.5", 41800, AdbServiceType.CONNECT))
        val deadline = System.nanoTime() + 3_000_000_000
        while (env.connectCalls.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(session.submitCode("123456") != null)
        gate.countDown() // the follower dial now completes with a stale generation
        Thread.sleep(300)
        assertTrue(session.state.value !is SelfAdbState.Connected)
        assertEquals(1, transports.size)
        assertTrue(!transports.single().isConnected)
        env.pairGate!!.countDown()
        env.scope.cancel()
    }
}
