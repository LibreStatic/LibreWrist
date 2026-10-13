package io.openshm.patcher.device.logs

import io.openshm.patcher.device.DisconnectReason
import io.openshm.patcher.device.LogLinkStatus
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Output of one shell command on the device. */
class LogShellResult(val exitCode: Int, val stdout: String)

/** One live link to a device. [close] ends this use of it. */
interface LogSession {
    suspend fun shell(command: String): LogShellResult

    /** Runs [command] until its output ends, calling [onLine] per line; cancelling stops it. */
    suspend fun stream(command: String, onLine: (String) -> Unit)

    fun close()
}

/** Where a device's link comes from (the paired watch, or the phone's own Wireless debugging). */
interface DeviceLogSource {
    /** Connects, or throws [LogLinkException] saying why not. */
    suspend fun connect(): LogSession

    /** Drops the previous link so the next [connect] starts clean. */
    fun reset()
}

class LogLinkException(val reason: DisconnectReason, cause: Throwable? = null) :
    Exception("Device log link failed: $reason", cause)

/** What the runner reports; the orchestration in `DeviceLogs` turns it into state. */
interface RunnerListener {
    fun status(status: LogLinkStatus)
    fun label(label: String?)
    fun packages(packages: List<String>)
}

/**
 * Keeps one device's capture going: connects through [source], finds the monitored apps, streams their
 * logcat into [sink], and reconnects when the link ends. After [maxFailures] failed attempts in a row it
 * reports Disconnected and waits for [retry]. All timing uses coroutine delays, so tests run on virtual time.
 */
class DeviceLogRunner(
    private val source: DeviceLogSource,
    private val targets: () -> PackageTargets,
    private val sink: LogSink,
    private val listener: RunnerListener,
    private val backoffMillis: List<Long> = listOf(3_000, 6_000, 12_000, 30_000),
    private val maxFailures: Int = 3,
    private val recheckMillis: Long = 30_000,
    private val pidRefreshMillis: Long = 15_000,
) {
    private val trigger = Channel<Unit>(Channel.CONFLATED)
    private val appCursor = ResumeCursor()
    private val systemCursor = ResumeCursor()

    /** Set once a logcat answered that it does not know `--uid`. */
    @Volatile
    private var uidUnsupported = false

    /** Reconnects now: cuts a backoff wait short, or wakes a Disconnected runner. Ignored while streaming. */
    fun retry() {
        trigger.trySend(Unit)
    }

    suspend fun run() {
        var failures = 0
        listener.status(LogLinkStatus.Connecting)
        while (true) {
            trigger.tryReceive() // a stale retry must not cut the next wait short
            var reason = DisconnectReason.Error
            var failed = false
            try {
                val session = source.connect()
                try {
                    failures = 0
                    runSession(session)
                } finally {
                    withContext(NonCancellable) { runCatching { session.close() } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LogLinkException) {
                failed = true
                reason = e.reason
            } catch (e: Exception) {
                failed = true
            }
            if (failed) failures++
            if (failed && (failures >= maxFailures || reason == DisconnectReason.NotPaired)) {
                listener.status(LogLinkStatus.Disconnected(reason))
                trigger.receive()
                failures = 0
                listener.status(LogLinkStatus.Connecting)
            } else {
                listener.status(LogLinkStatus.Reconnecting)
                withTimeoutOrNull(backoffMillis[minOf(failures, backoffMillis.lastIndex)]) { trigger.receive() }
            }
            source.reset()
        }
    }

    /** Returns when the link ended (a stream reached its end); throws when it broke. */
    private suspend fun runSession(session: LogSession) {
        listener.label(readLabel(session))
        var announced = false
        while (true) {
            val selected = resolve(session)
            listener.packages(selected.keys.sorted())
            if (!announced) {
                announced = true
                listener.status(LogLinkStatus.Streaming)
            }
            if (selected.isEmpty()) {
                delay(recheckMillis)
                continue
            }
            if (!streamUntilChange(session, selected)) return
        }
    }

    private suspend fun resolve(session: LogSession): Map<String, List<Int>> {
        val result = session.shell("pm list packages -U")
        if (result.exitCode != 0) throw LogLinkException(DisconnectReason.Error)
        return targets().select(PackageTargets.parsePackageUids(result.stdout))
    }

    /** Streams until the monitored uids change (true) or a stream ends (false). */
    private suspend fun streamUntilChange(session: LogSession, selected: Map<String, List<Int>>): Boolean = coroutineScope {
        val packages = selected.keys
        val uids = selected.values.flatten().distinct()
        val outcome = CompletableDeferred<Boolean>()
        fun guarded(block: suspend () -> Unit) = launch {
            try {
                block()
                outcome.complete(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                outcome.completeExceptionally(e)
            }
        }
        guarded { streamApps(session, uids, packages) }
        guarded { streamSystem(session, packages) }
        launch {
            try {
                while (true) {
                    delay(recheckMillis)
                    if (resolve(session).values.flatten().distinct().toSet() != uids.toSet()) {
                        outcome.complete(true)
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                outcome.completeExceptionally(e)
            }
        }
        try {
            outcome.await()
        } finally {
            coroutineContext.cancelChildren()
        }
    }

    /** Stream A: everything the monitored apps log, by uid; by pid when this logcat has no `--uid`. */
    private suspend fun streamApps(session: LogSession, uids: List<Int>, packages: Set<String>) {
        if (!uidUnsupported) {
            appCursor.resume()
            var index = 0
            var unsupported = false
            session.stream(LogCommands.forUids(uids, appCursor.since)) { line ->
                if (unsupported) return@stream
                if (index++ < USAGE_WINDOW && LogCommands.isUsageError(line)) {
                    unsupported = true
                    return@stream
                }
                if (appCursor.accept(line)) sink.append(line)
            }
            if (!unsupported) return
            uidUnsupported = true
        }
        streamAppsByPid(session, packages)
    }

    /** `--uid` fallback: the whole log, kept by the pids `pidof` reports for the apps (refreshed periodically). */
    private suspend fun streamAppsByPid(session: LogSession, packages: Set<String>) = coroutineScope {
        val pids = AtomicReference<Set<Int>>(emptySet())
        suspend fun refresh() {
            try {
                pids.set(LogCommands.parsePids(session.shell(LogCommands.pidof(packages)).stdout))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The stream itself ends when the link is dead; a failed refresh keeps the old set.
            }
        }
        refresh() // before the first line, so the history dump is not all dropped
        launch {
            while (true) {
                delay(pidRefreshMillis)
                refresh()
            }
        }
        appCursor.resume()
        session.stream(LogCommands.everything(appCursor.since)) { line ->
            val pid = LogCommands.pidOf(line)
            if (pid != null && pid in pids.get() && appCursor.accept(line)) sink.append(line)
        }
        coroutineContext.cancelChildren()
    }

    /** Stream B: `system_server` lines (process death, ANR, start proc) that name a monitored app. */
    private suspend fun streamSystem(session: LogSession, packages: Set<String>) {
        systemCursor.resume()
        session.stream(LogCommands.system(systemCursor.since)) { line ->
            if (LogFilter.keepSystemLine(line, packages) && systemCursor.accept(line)) sink.append(line)
        }
    }

    private suspend fun readLabel(session: LogSession): String? {
        suspend fun prop(name: String): String? = try {
            session.shell("getprop $name").takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf { LABEL_PART.matches(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val model = prop("ro.product.model")
        val release = prop("ro.build.version.release")
        return listOfNotNull(model, release?.let { "Android $it" }).joinToString(" · ").ifEmpty { null }
    }

    private companion object {
        /** A `--uid` error shows in the first lines; later text is a real log line. */
        const val USAGE_WINDOW = 5
        val LABEL_PART = Regex("[A-Za-z0-9][A-Za-z0-9 ._+-]{0,63}")
    }
}
