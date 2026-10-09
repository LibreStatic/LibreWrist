package io.openshm.patcher.adb

import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.util.concurrent.CountDownLatch

/** Scripted channel: reads come from [input]; writes are collected in [written]. */
class FakeChannel(
    private val input: ByteArray = ByteArray(0),
    private val blockUntilClosed: Boolean = false,
) : AdbChannel {
    val written = ByteArrayOutputStream()
    private var pos = 0
    private val closedLatch = CountDownLatch(1)

    @Volatile
    var closed = false
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (blockUntilClosed) {
            closedLatch.await()
            return -1
        }
        if (pos >= input.size) return -1
        val n = minOf(length, input.size - pos, 7) // small reads exercise reassembly
        System.arraycopy(input, pos, buffer, offset, n)
        pos += n
        return n
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        written.write(buffer, offset, length)
    }

    override fun close() {
        closed = true
        closedLatch.countDown()
    }
}

/** Transport whose [handler] maps a destination to a channel, or throws to refuse it. */
class FakeTransport(val handler: (String) -> AdbChannel) : AdbTransport {
    val opened = mutableListOf<String>()
    var closedCount = 0
    override var isConnected = true

    override fun open(destination: String): AdbChannel {
        opened += destination
        return handler(destination)
    }

    override fun close() {
        closedCount++
        isConnected = false
    }
}

fun refuse(): Nothing = throw ConnectException("Stream open actively rejected by remote peer.")

/** `shell,v2` framing for a finished command. */
fun v2Frames(stdout: String = "", stderr: String = "", exit: Int = 0): ByteArray {
    val out = ByteArrayOutputStream()
    fun frame(id: Int, payload: ByteArray) {
        out.write(id)
        out.write(payload.size and 0xff)
        out.write((payload.size shr 8) and 0xff)
        out.write((payload.size shr 16) and 0xff)
        out.write((payload.size shr 24) and 0xff)
        out.write(payload)
    }
    if (stdout.isNotEmpty()) frame(1, stdout.toByteArray())
    if (stderr.isNotEmpty()) frame(2, stderr.toByteArray())
    frame(3, byteArrayOf(exit.toByte()))
    return out.toByteArray()
}

/** A transport answering `shell,v2,raw:<cmd>` through [shell] and `exec:` through [exec]. */
fun scriptedTransport(
    shell: (String) -> ShellResult,
    exec: (String) -> AdbChannel = { error("unexpected $it") },
): FakeTransport = FakeTransport { dest ->
    if (dest.startsWith("shell,v2,raw:")) {
        val r = shell(dest.removePrefix("shell,v2,raw:"))
        FakeChannel(v2Frames(r.stdout, r.stderr, r.exitCode))
    } else {
        exec(dest)
    }
}

fun ok(stdout: String = "") = ShellResult(0, stdout, "")
