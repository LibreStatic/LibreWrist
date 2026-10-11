package io.openshm.patcher

import io.openshm.patcher.device.KeepAliveLink
import io.openshm.patcher.device.WatchKeepAlive
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeKeepAliveLink(override var isPaired: Boolean = true) : KeepAliveLink {
    var pings = 0
    var reconnects = 0
    var pingFails = false
    var reconnectFails = false

    override suspend fun ping() {
        pings++
        if (pingFails) throw IOException("dead")
    }

    override suspend fun reconnect() {
        reconnects++
        if (reconnectFails) throw IOException("gone")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WatchKeepAliveTest {
    private fun TestScope.keepAlive(link: FakeKeepAliveLink) =
        WatchKeepAlive(link, backgroundScope, { currentTime }, intervalMillis = 60_000)

    @Test
    fun pingsOncePerInterval() = runTest {
        val link = FakeKeepAliveLink()
        val k = keepAlive(link)
        k.start()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(0, link.pings)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, link.pings)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(3, link.pings)
        assertEquals(0, link.reconnects)
    }

    @Test
    fun failedPingReconnectsAndKeepsTryingOnTheNextTick() = runTest {
        val link = FakeKeepAliveLink().apply { pingFails = true; reconnectFails = true }
        val k = keepAlive(link)
        k.start()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, link.reconnects)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, link.pings)
        assertEquals(2, link.reconnects)
        assertTrue(k.isRunning)
    }

    @Test
    fun stopCancelsTheLoop() = runTest {
        val link = FakeKeepAliveLink()
        val k = keepAlive(link)
        k.start()
        advanceTimeBy(60_000)
        runCurrent()
        k.stop()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, link.pings)
        assertFalse(k.isRunning)
    }

    @Test
    fun noWorkWhenNothingIsPaired() = runTest {
        val link = FakeKeepAliveLink(isPaired = false)
        val k = keepAlive(link)
        k.start()
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(0, link.pings)
        assertFalse(k.isRunning)
    }

    @Test
    fun stopAfterEndsTheLoopOnceTheLimitPassed() = runTest {
        val link = FakeKeepAliveLink()
        val k = keepAlive(link)
        k.start()
        advanceTimeBy(60_000)
        runCurrent()
        k.stopAfter(150_000)
        advanceTimeBy(600_000)
        runCurrent()
        // Ticks at 60, 120 and 180 s are inside the limit (210 s); the one at 240 s ends it.
        assertEquals(3, link.pings)
        assertFalse(k.isRunning)
    }
}
