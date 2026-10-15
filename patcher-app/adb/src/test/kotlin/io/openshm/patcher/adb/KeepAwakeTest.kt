package io.openshm.patcher.adb

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KeepAwakeTest {
    private val dex = ByteArray(150_000) { (it * 31).toByte() }
    private val dexHash = MessageDigest.getInstance("SHA-256").digest(dex).joinToString("") { "%02x".format(it) }

    private fun frame(id: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(ShellV2.HEADER_SIZE + payload.size)
        ShellV2.putHeader(h, id, payload.size)
        System.arraycopy(payload, 0, h, ShellV2.HEADER_SIZE, payload.size)
        return h
    }

    /** A channel fed by the test while it runs: reads block until data arrives or the channel is closed. */
    private class LiveChannel : AdbChannel {
        private val queue = LinkedBlockingQueue<ByteArray>()
        private var current = ByteArray(0)
        private var pos = 0
        val written = ByteArrayOutputStream()
        val closedLatch = CountDownLatch(1)

        @Volatile
        var closed = false
            private set

        fun feed(bytes: ByteArray) = queue.put(bytes)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (pos >= current.size) {
                if (closed) return -1
                current = queue.poll(20, TimeUnit.MILLISECONDS) ?: ByteArray(0)
                pos = 0
            }
            val n = minOf(length, current.size - pos)
            System.arraycopy(current, pos, buffer, offset, n)
            pos += n
            return n
        }

        @Synchronized
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (closed) throw java.io.IOException("closed")
            written.write(buffer, offset, length)
        }

        override fun close() {
            closed = true
            closedLatch.countDown()
        }

        @Synchronized
        fun heartbeats(): Int = written.size() / (ShellV2.HEADER_SIZE + 1)
    }

    private fun stdout(text: String) = frame(1, text.toByteArray())

    @Test
    fun `command strings`() {
        assertEquals(
            "shell,v2,raw:export CLASSPATH=/data/local/tmp/openshm-keep-awake.dex; " +
                "exec app_process / --nice-name=openshm-keep-awake io.openshm.patcher.keepawake.KeepAwake 900000 45000",
            KeepAwakeCommands.run(900_000, 45_000),
        )
        assertEquals(
            "shell,v2,raw:head -c 123 > /data/local/tmp/openshm-keep-awake.dex.tmp && mv -f " +
                "/data/local/tmp/openshm-keep-awake.dex.tmp /data/local/tmp/openshm-keep-awake.dex",
            KeepAwakeCommands.push(123),
        )
        assertEquals("sha256sum /data/local/tmp/openshm-keep-awake.dex", KeepAwakeCommands.hash())
        for ((a, b) in listOf(0L to 1L, 1L to 0L, -1L to 5L)) {
            try { KeepAwakeCommands.run(a, b); fail("accepted $a $b") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun `install does nothing when the hash matches`() = runBlocking {
        val t = scriptedTransport({ ok("$dexHash  /data/local/tmp/openshm-keep-awake.dex\n") })
        KeepAwakeHelper(AdbClient(t)).install(dex)
        assertEquals(listOf("shell,v2,raw:sha256sum /data/local/tmp/openshm-keep-awake.dex"), t.opened)
    }

    @Test
    fun `install pushes framed stdin and re-checks the hash`() = runBlocking {
        var pushed = false
        val push = FakeChannel(v2Frames(), hangAtEnd = true)
        val t = FakeTransport { dest ->
            val cmd = dest.removePrefix("shell,v2,raw:")
            when {
                cmd.startsWith("head -c ${dex.size} ") -> { pushed = true; push }
                cmd.startsWith("sha256sum") ->
                    FakeChannel(v2Frames(if (pushed) "$dexHash  x\n" else "", exit = if (pushed) 0 else 1))
                else -> error("unexpected $dest")
            }
        }
        KeepAwakeHelper(AdbClient(t)).install(dex)
        assertEquals(3, t.opened.size)
        assertArrayEquals(dex, v2Stdin(push.written.toByteArray()))
        assertTrue(push.closed)
    }

    @Test
    fun `install fails when the push exits non-zero or the hash still differs`() {
        val failingPush = FakeTransport { dest ->
            if (dest.contains("head -c")) FakeChannel(v2Frames(stderr = "no space", exit = 1), hangAtEnd = true)
            else FakeChannel(v2Frames(exit = 1))
        }
        try { runBlocking { KeepAwakeHelper(AdbClient(failingPush)).install(dex) }; fail() } catch (_: AdbException) {}

        val wrongHash = FakeTransport { dest ->
            if (dest.contains("head -c")) FakeChannel(v2Frames(), hangAtEnd = true)
            else FakeChannel(v2Frames("${"0".repeat(64)}  x\n"))
        }
        try { runBlocking { KeepAwakeHelper(AdbClient(wrongHash)).install(dex) }; fail() } catch (_: AdbException) {}
    }

    @Test
    fun `hold waits for the ack, sends heartbeats and returns before the max`() = runBlocking {
        val ch = LiveChannel()
        val t = FakeTransport { ch }
        ch.feed(frame(2, "noise".toByteArray()))
        ch.feed(stdout("KEEP_AWAKE_ACQ"))
        ch.feed(stdout("UIRED\n"))
        // max 30_400 ms with the default 30 s margin: about 400 ms of holding.
        KeepAwakeHelper(AdbClient(t)).hold(30_400, 50, 45_000)
        assertTrue(ch.closed)
        assertTrue("heartbeats: ${ch.heartbeats()}", ch.heartbeats() in 3..12)
        assertTrue(ch.written.toByteArray().asList().chunked(ShellV2.HEADER_SIZE + 1).all { it[0].toInt() == 0 && it[5].toInt() == 1 })
        assertEquals(listOf(KeepAwakeCommands.run(30_400, 45_000)), t.opened)
    }

    @Test
    fun `hold throws on an error line, an early exit and a missing ack`() {
        fun failWith(feed: List<ByteArray>, ackTimeout: Long = 15_000): AdbException {
            val ch = LiveChannel().also { c -> feed.forEach(c::feed) }
            try {
                runBlocking { KeepAwakeHelper(AdbClient(FakeTransport { ch })).hold(60_000, 50, 45_000, ackTimeout) }
                fail("expected a failure")
            } catch (e: AdbException) {
                assertTrue(ch.closed)
                return e
            }
            error("unreachable")
        }
        assertTrue(failWith(listOf(stdout("KEEP_AWAKE_ERROR no power service\n"), frame(3, byteArrayOf(1)))).message!!.contains("no power service"))
        assertTrue(failWith(listOf(frame(2, "boom".toByteArray()), frame(3, byteArrayOf(1)))).message!!.contains("exited"))
        assertTrue(failWith(emptyList(), ackTimeout = 200) is AdbException.Timeout)
    }

    @Test
    fun `cancelling a hold closes the channel promptly`() = runBlocking {
        val ch = LiveChannel()
        ch.feed(stdout("KEEP_AWAKE_ACQUIRED\n"))
        val job = async(start = CoroutineStart.UNDISPATCHED) { KeepAwakeHelper(AdbClient(FakeTransport { ch })).hold(900_000, 50, 45_000) }
        val deadline = System.currentTimeMillis() + 5_000
        while (ch.heartbeats() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(ch.heartbeats() >= 2)
        job.cancel()
        assertTrue("channel not closed", ch.closedLatch.await(2, TimeUnit.SECONDS))
    }
}
