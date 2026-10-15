package io.openshm.patcher.device

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchAwakeTest {
    private class FakeHolder(
        val onPrepare: suspend (Int) -> Unit = {},
        val onHold: suspend (Int) -> Unit = {},
    ) : AwakeHolder {
        var prepares = 0
        var holds = 0
        var cancelled = false

        override suspend fun prepare() {
            onPrepare(++prepares)
        }

        override suspend fun holdOnce() {
            try {
                onHold(++holds)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private fun kotlinx.coroutines.test.TestScope.awake(holder: AwakeHolder, cap: Long = 3_600_000, logs: MutableList<String> = mutableListOf()) =
        WatchAwake(holder, { logs += it }, { currentTime }, cap, 5_000)

    @Test
    fun `returns the block result`() = runTest {
        val holder = FakeHolder(onHold = { delay(1_000_000) })
        assertEquals(42, awake(holder).around { 42 })
    }

    @Test
    fun `holder failures never fail or delay the block`() = runTest {
        val holder = FakeHolder(onPrepare = { throw IOException("no link") }, onHold = { throw IllegalStateException("boom") })
        val result = awake(holder).around { delay(100); "done" }
        assertEquals("done", result)
        assertEquals(100, currentTime)
    }

    @Test
    fun `renews after a normal return and retries after a failure`() = runTest {
        val holder = FakeHolder(onHold = { n ->
            delay(60_000)
            if (n == 2) throw IOException("lost")
        })
        awake(holder).around { delay(10 * 60_000L) }
        // 60 s holds, a failure on the second one, then a 5 s retry delay: more than 5 holds in 10 min.
        assertTrue("holds: ${holder.holds}", holder.holds >= 8)
        assertEquals(1, holder.prepares)
        assertTrue(holder.cancelled)
    }

    @Test
    fun `retries a failed prepare a few times and then gives up`() = runTest {
        val logs = mutableListOf<String>()
        val holder = FakeHolder(onPrepare = { throw IOException("push failed") })
        awake(holder, logs = logs).around { delay(600_000) }
        assertEquals(3, holder.prepares)
        assertEquals(0, holder.holds)
        assertTrue(logs.any { it.contains("giving up") })
    }

    @Test
    fun `stops at the session cap`() = runTest {
        val logs = mutableListOf<String>()
        val holder = FakeHolder(onHold = { delay(60_000) })
        awake(holder, cap = 300_000, logs = logs).around { delay(3_600_000) }
        assertEquals(5, holder.holds)
        assertTrue(logs.any { it.contains("session cap") })
    }

    @Test
    fun `an instant holder does not spin`() = runTest {
        val holder = FakeHolder()
        awake(holder).around { delay(60_000) }
        assertTrue("holds: ${holder.holds}", holder.holds in 1..13)
    }

    @Test
    fun `cancels the holder when the block ends, normally or not`() = runTest {
        val started = CompletableDeferred<Unit>()
        val normal = FakeHolder(onHold = { started.complete(Unit); delay(Long.MAX_VALUE) })
        awake(normal).around { started.await() }
        assertTrue(normal.cancelled)

        val failing = FakeHolder(onHold = { delay(Long.MAX_VALUE) })
        try {
            awake(failing).around { delay(10); throw IOException("install failed") }
            fail("expected the block's exception")
        } catch (e: IOException) {
            assertEquals("install failed", e.message)
        }
        assertTrue(failing.cancelled)
        assertFalse(failing.holds == 0)
    }
}
