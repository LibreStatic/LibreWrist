package io.openshm.patcher.adb

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamShellTest {
    private fun stream(bytes: ByteArray): List<String> = runBlocking {
        val lines = mutableListOf<String>()
        val transport = FakeTransport { FakeChannel(bytes) } // the fake delivers 7 bytes per read
        AdbClient(transport).streamShell("logcat") { lines += it }
        assertEquals(listOf("shell:logcat"), transport.opened)
        lines
    }

    @Test
    fun `lines split across read boundaries are reassembled`() {
        assertEquals(
            listOf("first line here", "second", "", "third one"),
            stream("first line here\nsecond\n\nthird one\n".toByteArray()),
        )
    }

    @Test
    fun `CRLF endings lose the carriage return`() {
        assertEquals(listOf("a", "b c"), stream("a\r\nb c\r\n".toByteArray()))
    }

    @Test
    fun `a multibyte character cut by a read is decoded intact`() {
        val text = "ééééééé€€€ ✓\n"
        assertEquals(listOf(text.trimEnd('\n')), stream(text.toByteArray()))
    }

    @Test
    fun `the trailing partial line is emitted at end of stream`() {
        assertEquals(listOf("one", "partial"), stream("one\npartial".toByteArray()))
    }

    @Test
    fun `an empty stream emits nothing`() {
        assertEquals(emptyList<String>(), stream(ByteArray(0)))
    }

    @Test
    fun `cancelling the coroutine closes the channel`() = runBlocking {
        val channel = FakeChannel(blockUntilClosed = true)
        val transport = FakeTransport { channel }
        val job = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            AdbClient(transport).streamShell("logcat") { }
        }
        var waited = 0
        while (transport.opened.isEmpty() && waited++ < 200) delay(10)
        assertTrue(transport.opened.isNotEmpty())
        job.cancelAndJoin()
        var spins = 0
        while (!channel.closed && spins++ < 200) delay(10)
        assertTrue(channel.closed)
    }
}
