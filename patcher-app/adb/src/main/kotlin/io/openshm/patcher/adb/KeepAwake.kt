package io.openshm.patcher.adb

import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/** Command strings for the keep-awake helper that runs on the watch (see `patcher-app/keep-awake`). */
internal object KeepAwakeCommands {
    const val REMOTE_PATH = "/data/local/tmp/openshm-keep-awake.dex"
    const val MAIN_CLASS = "io.openshm.patcher.keepawake.KeepAwake"
    const val ACQUIRED = "KEEP_AWAKE_ACQUIRED"
    const val RELEASED = "KEEP_AWAKE_RELEASED"
    const val ERROR = "KEEP_AWAKE_ERROR"

    /** `exec` matters: adbd sends SIGHUP to the shell pid when the stream dies, so the helper must be that pid. */
    fun run(maxMillis: Long, heartbeatTimeoutMillis: Long): String {
        require(maxMillis > 0) { "maxMillis must be positive" }
        require(heartbeatTimeoutMillis > 0) { "heartbeatTimeoutMillis must be positive" }
        return "shell,v2,raw:export CLASSPATH=$REMOTE_PATH; " +
            "exec app_process / --nice-name=openshm-keep-awake $MAIN_CLASS $maxMillis $heartbeatTimeoutMillis"
    }

    fun push(sizeBytes: Long): String {
        require(sizeBytes > 0) { "size must be positive" }
        return "shell,v2,raw:head -c $sizeBytes > $REMOTE_PATH.tmp && mv -f $REMOTE_PATH.tmp $REMOTE_PATH"
    }

    fun hash(): String = "sha256sum $REMOTE_PATH"

    private val sha256Line = Regex("""^([0-9a-fA-F]{64})(\s|$)""")

    /** The digest at the start of a `sha256sum` line, lowercase; null when there is none. */
    fun parseSha256(output: String): String? =
        output.lineSequence().mapNotNull { sha256Line.find(it.trim())?.groupValues?.get(1)?.lowercase() }.firstOrNull()
}

/**
 * Keeps the watch's CPU and screen awake through a helper process on the watch that holds a
 * wakelock. Nothing persistent changes on the watch: the wakelock belongs to a process that ends
 * when our heartbeats stop, the stream closes, or its own time limit passes.
 */
class KeepAwakeHelper(private val client: AdbClient) {

    /** Puts [dex] on the watch unless the file there already has the same SHA-256. */
    suspend fun install(dex: ByteArray) {
        require(dex.isNotEmpty()) { "The keep-awake helper is empty" }
        val expected = sha256Hex(dex)
        if (remoteHash() == expected) return

        val command = KeepAwakeCommands.push(dex.size.toLong())
        val result = client.useChannel(command, PUSH_TIMEOUT_MS) { channel ->
            val buf = ByteArray(ShellV2.HEADER_SIZE + CHUNK)
            var sent = 0
            while (sent < dex.size) {
                val n = minOf(CHUNK, dex.size - sent)
                System.arraycopy(dex, sent, buf, ShellV2.HEADER_SIZE, n)
                ShellV2.putHeader(buf, ShellV2.ID_STDIN, n)
                channel.write(buf, 0, ShellV2.HEADER_SIZE + n)
                sent += n
            }
            // Ends on the exit packet, never on EOF (see LibAdbChannel.read).
            ShellV2.readResult(channel)
        }
        if (!result.isSuccess) throw AdbException.CommandFailed(command, result)
        if (remoteHash() != expected) throw AdbException.Protocol("The keep-awake helper on the watch does not match after the push")
    }

    private suspend fun remoteHash(): String? =
        client.shell(KeepAwakeCommands.hash(), HASH_TIMEOUT_MS).takeIf { it.isSuccess }?.let { KeepAwakeCommands.parseSha256(it.stdout) }

    /**
     * Runs the helper and keeps it alive with a heartbeat every [heartbeatIntervalMillis]. Returns
     * normally [renewMarginMillis] before [maxMillis] elapsed since the acknowledgement, so the caller
     * can start a new hold before the helper's own limit. Throws when the helper fails, does not
     * acknowledge within [ackTimeoutMillis], or a write fails. Cancelling closes the channel; the
     * helper then releases the wakelock and exits. The channel is always closed on the way out.
     */
    suspend fun hold(
        maxMillis: Long,
        heartbeatIntervalMillis: Long,
        heartbeatTimeoutMillis: Long,
        ackTimeoutMillis: Long = ACK_TIMEOUT_MS,
        renewMarginMillis: Long = RENEW_MARGIN_MS,
    ) {
        require(heartbeatIntervalMillis > 0) { "heartbeatIntervalMillis must be positive" }
        val command = KeepAwakeCommands.run(maxMillis, heartbeatTimeoutMillis)
        val job = currentCoroutineContext()[Job]
        client.useChannel(command, Long.MAX_VALUE) { channel ->
            val timedOut = AtomicBoolean(false)
            val watchdog = Executors.newSingleThreadScheduledExecutor(DaemonThreads)
            val ackTimer = watchdog.schedule({ timedOut.set(true); runCatching { channel.close() } }, ackTimeoutMillis, TimeUnit.MILLISECONDS)
            try {
                try {
                    awaitAck(channel)
                } catch (e: IOException) {
                    if (timedOut.get()) throw AdbException.Timeout("the keep-awake helper did not acknowledge")
                    throw e
                } finally {
                    ackTimer.cancel(false)
                }
            } finally {
                watchdog.shutdownNow()
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxOf(0L, maxMillis - renewMarginMillis))
            val beat = ByteArray(ShellV2.HEADER_SIZE + 1)
            ShellV2.putHeader(beat, ShellV2.ID_STDIN, 1)
            beat[ShellV2.HEADER_SIZE] = 1
            while (job?.isActive != false && System.nanoTime() < deadline) {
                channel.write(beat, 0, beat.size)
                var slept = 0L
                while (slept < heartbeatIntervalMillis && job?.isActive != false && System.nanoTime() < deadline) {
                    val slice = minOf(SLICE_MS, heartbeatIntervalMillis - slept)
                    Thread.sleep(slice)
                    slept += slice
                }
            }
        }
    }

    /** Reads shell,v2 packets until the ACQUIRED line; stderr is ignored. */
    private fun awaitAck(channel: AdbChannel) {
        var acked = false
        var failure: String? = null
        val splitter = Utf8LineSplitter { line ->
            val text = line.trim()
            when {
                text == KeepAwakeCommands.ACQUIRED -> acked = true
                text.startsWith(KeepAwakeCommands.ERROR) && failure == null ->
                    failure = text.removePrefix(KeepAwakeCommands.ERROR).trim().ifEmpty { "unknown error" }
            }
        }
        val header = ByteArray(ShellV2.HEADER_SIZE)
        while (!acked) {
            if (!readFully(channel, header, ShellV2.HEADER_SIZE)) throw AdbException.Protocol("The keep-awake helper closed the stream before it started")
            val id = header[0].toInt() and 0xff
            val length = (header[1].toInt() and 0xff) or ((header[2].toInt() and 0xff) shl 8) or
                ((header[3].toInt() and 0xff) shl 16) or ((header[4].toInt() and 0xff) shl 24)
            if (length < 0 || length > MAX_PACKET) throw AdbException.Protocol("Bad shell packet length $length")
            val payload = ByteArray(length)
            if (!readFully(channel, payload, length) && length > 0) throw AdbException.Protocol("Truncated shell packet")
            when (id) {
                ID_STDOUT -> splitter.feed(payload, 0, length)
                ID_EXIT -> {
                    splitter.finish()
                    if (!acked) throw AdbException.Protocol(failure ?: "The keep-awake helper exited before it started (code ${if (length > 0) payload[0].toInt() and 0xff else 0})")
                }
                else -> Unit
            }
            failure?.let { if (!acked) throw AdbException.Protocol("Keep-awake helper: $it") }
        }
    }

    /** Fills [count] bytes; false when the stream ended before the first byte. */
    private fun readFully(channel: AdbChannel, target: ByteArray, count: Int): Boolean {
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

    private object DaemonThreads : ThreadFactory {
        override fun newThread(r: Runnable) = Thread(r, "keep-awake-watchdog").apply { isDaemon = true }
    }

    companion object {
        const val ACK_TIMEOUT_MS = 15_000L
        const val RENEW_MARGIN_MS = 30_000L
        private const val PUSH_TIMEOUT_MS = 60_000L
        private const val HASH_TIMEOUT_MS = 15_000L
        private const val CHUNK = 64 * 1024
        private const val SLICE_MS = 250L
        private const val MAX_PACKET = 1024 * 1024
        private const val ID_STDOUT = 1
        private const val ID_EXIT = 3

        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
