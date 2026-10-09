package io.openshm.patcher.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicReference

/**
 * A connected ADB session: run shell commands, or open raw service streams for installs and pulls.
 * All suspend functions run the blocking transport on an IO thread and honour their timeout.
 */
class AdbClient(
    private val transport: AdbTransport,
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MS,
) : Closeable {

    val isConnected: Boolean get() = transport.isConnected

    /**
     * Runs [command] on the device. Uses `shell,v2` (separate stdout/stderr and a real exit code);
     * if the daemon refuses it, falls back to a plain shell with an exit-code marker.
     *
     * [command] is passed to the device shell as is; build it from validated parts only.
     */
    suspend fun shell(command: String, timeoutMillis: Long = defaultTimeoutMillis): ShellResult {
        val channel = AtomicReference<AdbChannel?>()
        return blockingIo(timeoutMillis, "shell: ${command.take(60)}", onAbort = { channel.get()?.close() }) {
            runShell(command) { channel.set(it) }
        }
    }

    /**
     * Opens [destination] and runs [block] with the channel, closing it afterwards. A timeout or
     * cancellation closes the channel to unblock [block]. [block] runs on an IO thread.
     */
    suspend fun <T> useChannel(
        destination: String,
        timeoutMillis: Long = defaultTimeoutMillis,
        block: (AdbChannel) -> T,
    ): T {
        val channel = AtomicReference<AdbChannel?>()
        return blockingIo(timeoutMillis, "stream: ${destination.take(60)}", onAbort = { channel.get()?.close() }) {
            val opened = openChannel(destination)
            channel.set(opened)
            opened.use(block)
        }
    }

    override fun close() {
        transport.close()
    }

    private fun openChannel(destination: String): AdbChannel {
        if (!transport.isConnected) throw AdbException.NotConnected()
        return transport.open(destination)
    }

    private fun runShell(command: String, publish: (AdbChannel) -> Unit): ShellResult {
        val v2 = try {
            openChannel("shell,v2,raw:$command")
        } catch (e: ConnectException) {
            null
        }
        if (v2 != null) {
            publish(v2)
            return v2.use { ShellV2.readResult(it) }
        }
        val marker = LegacyShell.newMarker()
        val legacy = openChannel("shell:" + LegacyShell.wrap(command, marker))
        publish(legacy)
        return legacy.use { LegacyShell.parse(it.readFully(), marker) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** Connects through [backend] and wraps the transport. */
        suspend fun connect(
            backend: AdbBackend,
            host: String,
            port: Int,
            timeoutMillis: Long = 15_000,
        ): AdbClient {
            val transport = try {
                blockingIo(timeoutMillis + 2_000, "connecting to $host:$port") {
                    backend.connect(host, port, timeoutMillis)
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                throw AdbErrors.mapConnect(t)
            }
            return AdbClient(transport)
        }
    }
}

internal fun AdbChannel.readFully(limit: Int = ShellV2.MAX_OUTPUT_BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf, 0, buf.size)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > limit) throw AdbException.Protocol("Command output exceeds $limit bytes")
    }
    return out.toByteArray()
}

/** Decoder of the `shell,v2` framing: 1-byte id, 4-byte little-endian length, payload. */
internal object ShellV2 {
    const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024
    private const val ID_STDOUT = 1
    private const val ID_STDERR = 2
    private const val ID_EXIT = 3

    fun readResult(channel: AdbChannel): ShellResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var exit: Int? = null
        val header = ByteArray(5)
        while (true) {
            if (!readExactly(channel, header, 5)) break
            val id = header[0].toInt() and 0xff
            val length = (header[1].toInt() and 0xff) or
                ((header[2].toInt() and 0xff) shl 8) or
                ((header[3].toInt() and 0xff) shl 16) or
                ((header[4].toInt() and 0xff) shl 24)
            if (length < 0 || length > MAX_OUTPUT_BYTES) throw AdbException.Protocol("Bad shell packet length $length")
            val payload = ByteArray(length)
            if (!readExactly(channel, payload, length)) throw AdbException.Protocol("Truncated shell packet")
            when (id) {
                ID_STDOUT -> stdout.write(payload)
                ID_STDERR -> stderr.write(payload)
                ID_EXIT -> exit = if (length >= 1) payload[0].toInt() and 0xff else 0
                else -> Unit // stdin close, window size: not meaningful for output
            }
            if (stdout.size() + stderr.size() > MAX_OUTPUT_BYTES) {
                throw AdbException.Protocol("Command output exceeds $MAX_OUTPUT_BYTES bytes")
            }
            if (id == ID_EXIT) break
        }
        val code = exit ?: throw AdbException.Protocol("The shell ended without an exit status")
        return ShellResult(code, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    /** Fills the first [count] bytes of [target]; false when the stream ended before the first byte. */
    private fun readExactly(channel: AdbChannel, target: ByteArray, count: Int): Boolean {
        var done = 0
        while (done < count) {
            val n = channel.read(target, done, count - done)
            if (n < 0) {
                if (done == 0) return false
                throw AdbException.Protocol("Stream ended inside a packet")
            }
            done += n
        }
        return true
    }
}

/** Exit-code wrapper for daemons without `shell,v2`. stderr is merged into stdout there. */
internal object LegacyShell {
    fun newMarker(): String = "__OPENSHM_EXIT_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12) + "__"

    fun wrap(command: String, marker: String): String = "( $command ) 2>&1; echo \"$marker\$?\""

    fun parse(output: ByteArray, marker: String): ShellResult {
        val text = output.toString(Charsets.UTF_8)
        val at = text.lastIndexOf(marker)
        if (at < 0) throw AdbException.Protocol("The shell did not report an exit status")
        val code = text.substring(at + marker.length).trim().toIntOrNull()
            ?: throw AdbException.Protocol("Unreadable exit status")
        return ShellResult(code, text.substring(0, at), "")
    }
}
