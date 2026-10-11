package io.openshm.patcher

import io.openshm.patcher.adb.AbortScope
import io.openshm.patcher.adb.AdbBackend
import io.openshm.patcher.adb.AdbChannel
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.adb.AdbTransport
import io.openshm.patcher.adb.PairingFailure
import io.openshm.patcher.device.WatchHostStore
import io.openshm.patcher.device.WatchLink
import java.net.ConnectException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeWatchBackend(
    private val refusedPorts: Set<Int> = emptySet(),
    private val pairError: Throwable? = null,
) : AdbBackend {
    val connects = mutableListOf<Pair<String, Int>>()
    val pairs = mutableListOf<Triple<String, Int, String>>()
    val transports = mutableListOf<FakeWatchTransport>()

    override fun pair(host: String, port: Int, code: String, abort: AbortScope) {
        pairs += Triple(host, port, code)
        pairError?.let { throw it }
    }

    override fun connect(host: String, port: Int, timeoutMillis: Long, abort: AbortScope): AdbTransport {
        connects += host to port
        if (port in refusedPorts) throw ConnectException("Connection refused")
        return FakeWatchTransport().also { transports += it }
    }
}

private class FakeWatchTransport : AdbTransport {
    override var isConnected = true
    override fun open(destination: String): AdbChannel = throw ConnectException("not scripted")
    override fun close() {
        isConnected = false
    }
}

private class MemoryHostStore(override var host: String? = null, override var serialPrefix: String? = null) : WatchHostStore

private fun connectService(host: String, port: Int, name: String = "adb-$port") =
    AdbService(name, host, port, AdbServiceType.CONNECT)

class WatchLinkTest {
    private fun link(
        backend: FakeWatchBackend,
        store: WatchHostStore,
        services: List<AdbService>,
        timeoutMillis: Long = 300,
    ) = WatchLink(backend, { flowOf(services) }, store, timeoutMillis)

    @Test
    fun staleRefusedPortIsSkippedAndTheLiveOneUsed() = runBlocking {
        val backend = FakeWatchBackend(refusedPorts = setOf(40001))
        val l = link(backend, MemoryHostStore("192.0.2.9"), listOf(connectService("192.0.2.9", 40001), connectService("192.0.2.9", 40002)))
        val client = l.client()
        assertTrue(client.isConnected)
        assertEquals(listOf("192.0.2.9" to 40001, "192.0.2.9" to 40002), backend.connects)
    }

    @Test
    fun servicesOfOtherHostsAreNeverDialled() = runBlocking {
        val backend = FakeWatchBackend()
        val l = link(backend, MemoryHostStore("192.0.2.9"), listOf(connectService("192.0.2.77", 41000), connectService("192.0.2.9", 42000)))
        l.client()
        assertEquals(listOf("192.0.2.9" to 42000), backend.connects)
    }

    @Test
    fun noServiceForTheHostIsAnError() = runBlocking {
        val backend = FakeWatchBackend()
        val l = link(backend, MemoryHostStore("192.0.2.9"), listOf(connectService("192.0.2.77", 41000)))
        try {
            l.client()
            fail("expected an error")
        } catch (e: AdbException.Discovery) {
            assertTrue(backend.connects.isEmpty())
        }
    }

    @Test
    fun onlyRefusedPortsReportsTheRefusal() = runBlocking {
        val backend = FakeWatchBackend(refusedPorts = setOf(40001))
        val l = link(backend, MemoryHostStore("192.0.2.9"), listOf(connectService("192.0.2.9", 40001)))
        try {
            l.client()
            fail("expected an error")
        } catch (e: AdbException.ConnectionFailed) {
            assertEquals(1, backend.connects.size)
        }
    }

    @Test
    fun withoutAPairedWatchThePairingIsRequired() = runBlocking {
        val l = link(FakeWatchBackend(), MemoryHostStore(null), emptyList())
        try {
            l.client()
            fail("expected an error")
        } catch (e: AdbException.PairingRequired) {
        }
    }

    @Test
    fun aLiveClientIsReusedAndADroppedOneReplaced() = runBlocking {
        val backend = FakeWatchBackend()
        val l = link(backend, MemoryHostStore("192.0.2.9"), listOf(connectService("192.0.2.9", 42000)))
        val first = l.client()
        assertSame(first, l.client())
        assertEquals(1, backend.connects.size)
        backend.transports.single().close()
        val second = l.client()
        assertFalse(first === second)
        assertEquals(2, backend.connects.size)
    }

    @Test
    fun pairRemembersTheHostAndConnects() = runBlocking {
        val backend = FakeWatchBackend()
        val store = MemoryHostStore()
        val l = link(backend, store, listOf(connectService("192.0.2.9", 42000)))
        l.pair("192.0.2.9", 37115, "123456")
        assertEquals(listOf(Triple("192.0.2.9", 37115, "123456")), backend.pairs)
        assertEquals("192.0.2.9", store.host)
        assertEquals(listOf("192.0.2.9" to 42000), backend.connects)
    }

    @Test
    fun aFailedPairingRemembersNothing() = runBlocking {
        val backend = FakeWatchBackend(pairError = RuntimeException("Exchanging message wasn't successful"))
        val store = MemoryHostStore()
        val l = link(backend, store, emptyList())
        try {
            l.pair("192.0.2.9", 37115, "123456")
            fail("expected an error")
        } catch (e: AdbException.Pairing) {
            assertEquals(PairingFailure.WRONG_CODE, e.reason)
        }
        assertNull(store.host)
    }

    @Test
    fun pairSucceedsEvenIfTheConnectServiceIsNotUpYet() = runBlocking {
        val store = MemoryHostStore()
        val l = link(FakeWatchBackend(), store, emptyList(), timeoutMillis = 100)
        l.pair("192.0.2.9", 37115, "123456")
        assertEquals("192.0.2.9", store.host)
    }

    @Test
    fun serialPrefixIsTheInstanceNameWithoutTheRandomSuffix() {
        assertEquals("adb-R5CT123-", WatchLink.serialPrefixOf("adb-R5CT123-aBc9Xz"))
        assertNull(WatchLink.serialPrefixOf("adb-9999"))
        assertNull(WatchLink.serialPrefixOf("something-else-abc"))
        assertNull(WatchLink.serialPrefixOf(""))
    }

    @Test
    fun candidatesPreferTheRememberedHostAndFallBackToTheSerialPrefix() {
        val sameIp = connectService("192.0.2.9", 40001, "adb-R5CT123-aaa")
        val moved = connectService("192.0.2.50", 40002, "adb-R5CT123-bbb")
        val other = connectService("192.0.2.60", 40003, "adb-OTHER99-ccc")
        assertEquals(listOf(sameIp), WatchLink.candidates(listOf(moved, sameIp, other), "192.0.2.9", "adb-R5CT123-"))
        assertEquals(listOf(moved), WatchLink.candidates(listOf(moved, other), "192.0.2.9", "adb-R5CT123-"))
        assertTrue(WatchLink.candidates(listOf(moved, other), "192.0.2.9", null).isEmpty())
        assertTrue(WatchLink.candidates(listOf(other), "192.0.2.9", "adb-R5CT123-").isEmpty())
    }

    @Test
    fun watchWithANewAddressIsFoundBySerialAndTheStoreIsUpdated() = runBlocking {
        val backend = FakeWatchBackend()
        val store = MemoryHostStore("192.0.2.9", "adb-R5CT123-")
        val l = link(backend, store, listOf(connectService("192.0.2.50", 40002, "adb-R5CT123-zzz")))
        assertTrue(l.client().isConnected)
        assertEquals(listOf("192.0.2.50" to 40002), backend.connects)
        assertEquals("192.0.2.50", store.host)
    }

    @Test
    fun connectingRecordsTheSerialPrefix() = runBlocking {
        val store = MemoryHostStore("192.0.2.9")
        link(FakeWatchBackend(), store, listOf(connectService("192.0.2.9", 40001, "adb-R5CT123-aaa"))).client()
        assertEquals("adb-R5CT123-", store.serialPrefix)
    }
}
