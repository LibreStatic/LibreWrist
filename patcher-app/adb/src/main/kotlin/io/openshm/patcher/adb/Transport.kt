package io.openshm.patcher.adb

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/**
 * Seam between this library's logic and libadb. Everything above it is plain Kotlin and is tested
 * with fakes; [LibAdbBackend] is the only implementation that touches libadb.
 */
interface AdbBackend {
    /** Pairs with the Wireless debugging pairing service. Blocking. */
    fun pair(host: String, port: Int, code: String)

    /** Opens an authenticated connection. Blocking. */
    fun connect(host: String, port: Int, timeoutMillis: Long): AdbTransport
}

/** One authenticated ADB connection that can open service streams. */
interface AdbTransport : Closeable {
    val isConnected: Boolean

    /**
     * Opens a service stream such as `shell,v2,raw:ls` or `exec:cat /x`. Blocking.
     *
     * @throws java.net.ConnectException if the daemon refuses the service.
     */
    fun open(destination: String): AdbChannel
}

/** A bidirectional byte stream to one service. */
interface AdbChannel : Closeable {
    /** Returns the number of bytes read, or -1 at end of stream. Blocking. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Writes all the bytes. Blocking. */
    fun write(buffer: ByteArray, offset: Int, length: Int)
}

/** Outcome of a shell command. */
data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val isSuccess: Boolean get() = exitCode == 0

    /** Returns this result or throws [AdbException.CommandFailed]. */
    fun requireSuccess(command: String = ""): ShellResult =
        if (isSuccess) this else throw AdbException.CommandFailed(command, this)
}

private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs blocking [block] on an IO thread with a timeout. libadb's calls ignore thread interruption,
 * so on timeout or cancellation [onAbort] must unblock them (normally by closing the channel or
 * transport); the abandoned thread then ends by itself.
 */
internal suspend fun <T> blockingIo(
    timeoutMillis: Long,
    what: String,
    onAbort: () -> Unit = {},
    block: () -> T,
): T {
    val job = ioScope.async { block() }
    try {
        return withTimeout(timeoutMillis) { job.await() }
    } catch (e: TimeoutCancellationException) {
        runCatching(onAbort)
        job.cancel()
        throw AdbException.Timeout(what)
    } catch (e: CancellationException) {
        runCatching(onAbort)
        job.cancel()
        throw e
    }
}
