package io.openshm.patcher.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbClientTest {
    @Test
    fun `shell v2 returns split streams and the exit code`() = runBlocking {
        val transport = FakeTransport { FakeChannel(v2Frames("out\n", "err\n", 3)) }
        val r = AdbClient(transport).shell("ls /nope")
        assertEquals(ShellResult(3, "out\n", "err\n"), r)
        assertEquals(listOf("shell,v2,raw:ls /nope"), transport.opened)
    }

    @Test
    fun `shell v2 handles output larger than one read and multi-byte text`() = runBlocking {
        val big = "é".repeat(5000)
        val transport = FakeTransport { FakeChannel(v2Frames(big, "", 0)) }
        assertEquals(big, AdbClient(transport).shell("x").stdout)
    }

    @Test
    fun `shell v2 without exit packet is a protocol error`() = runBlocking {
        val noExit = v2Frames("hi", "", 0).let { it.copyOf(it.size - 6) }
        val transport = FakeTransport { FakeChannel(noExit) }
        try {
            AdbClient(transport).shell("x")
            fail()
        } catch (e: AdbException.Protocol) {
            assertTrue(e.message!!.contains("exit status"))
        }
    }

    @Test
    fun `truncated frame is a protocol error`() = runBlocking {
        val cut = v2Frames("hello", "", 0).copyOf(7)
        try {
            AdbClient(FakeTransport { FakeChannel(cut) }).shell("x")
            fail()
        } catch (e: AdbException.Protocol) {
        }
    }

    @Test
    fun `falls back to the exit-code wrapper when shell v2 is refused`() = runBlocking {
        var marker = ""
        val transport = FakeTransport { dest ->
            if (dest.startsWith("shell,v2")) refuse()
            marker = Regex("__OPENSHM_EXIT_[0-9a-f]+__").find(dest)!!.value
            FakeChannel(("line1\nline2\n$marker" + "7\n").toByteArray())
        }
        val r = AdbClient(transport).shell("do-thing")
        assertEquals(ShellResult(7, "line1\nline2\n", ""), r)
        assertTrue(transport.opened[1].startsWith("shell:( do-thing ) 2>&1; echo \"__OPENSHM_EXIT_"))
    }

    @Test
    fun `legacy parse uses the last marker and rejects missing ones`() {
        val m = "__M__"
        assertEquals(ShellResult(0, "a__M__x\n", ""), LegacyShell.parse("a__M__x\n${m}0\n".toByteArray(), m))
        try {
            LegacyShell.parse("nothing".toByteArray(), m)
            fail()
        } catch (e: AdbException.Protocol) {
        }
        try {
            LegacyShell.parse("${m}abc".toByteArray(), m)
            fail()
        } catch (e: AdbException.Protocol) {
        }
    }

    @Test
    fun `shell times out and closes the stuck channel`() = runBlocking {
        val stuck = FakeChannel(blockUntilClosed = true)
        val client = AdbClient(FakeTransport { stuck })
        try {
            client.shell("sleep 100", timeoutMillis = 150)
            fail()
        } catch (e: AdbException.Timeout) {
            assertTrue(stuck.closed)
        }
    }

    @Test
    fun `requireSuccess throws a typed error`() {
        try {
            ShellResult(2, "", "boom").requireSuccess("cmd x")
            fail()
        } catch (e: AdbException.CommandFailed) {
            assertEquals(2, e.result.exitCode)
            assertTrue(e.message!!.contains("boom"))
        }
    }

    @Test
    fun `disconnected transport reports NotConnected`() = runBlocking {
        val transport = FakeTransport { FakeChannel() }
        transport.isConnected = false
        try {
            AdbClient(transport).shell("x")
            fail()
        } catch (e: AdbException.NotConnected) {
        }
    }

    @Test
    fun `connect maps backend failures to typed errors`() = runBlocking {
        fun backendThrowing(t: Throwable) = object : AdbBackend {
            override fun pair(host: String, port: Int, code: String) = Unit
            override fun connect(host: String, port: Int, timeoutMillis: Long): AdbTransport = throw t
        }
        try {
            AdbClient.connect(backendThrowing(java.net.ConnectException("refused")), "h", 1)
            fail()
        } catch (e: AdbException.ConnectionFailed) {
        }
        try {
            AdbClient.connect(backendThrowing(java.net.SocketTimeoutException("t")), "h", 1)
            fail()
        } catch (e: AdbException.Timeout) {
        }
        val ok = AdbClient.connect(
            object : AdbBackend {
                override fun pair(host: String, port: Int, code: String) = Unit
                override fun connect(host: String, port: Int, timeoutMillis: Long) = FakeTransport { FakeChannel() }
            },
            "h",
            1,
        )
        assertTrue(ok.isConnected)
    }
}
