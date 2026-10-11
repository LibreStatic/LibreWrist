package io.openshm.patcher.device

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** What the keep-alive needs from the watch link; the real one is [WatchLink], tests use a fake. */
interface KeepAliveLink {
    /** A watch was paired (its host is remembered). */
    val isPaired: Boolean

    /** Runs a trivial command over the live link; throws when the link is dead. */
    suspend fun ping()

    /** Drops the dead link and connects again; throws when the watch cannot be reached. */
    suspend fun reconnect()
}

internal class AdbKeepAliveLink(private val link: WatchLink) : KeepAliveLink {
    private companion object {
        const val PING_TIMEOUT_MS = 20_000L
    }

    override val isPaired: Boolean get() = link.rememberedHost != null

    override suspend fun ping() {
        // A half-open TCP link can block a read for a long time; treat a slow answer as dead.
        withTimeout(PING_TIMEOUT_MS) { link.client().shell("echo").requireSuccess("echo") }
    }

    override suspend fun reconnect() {
        link.forget()
        link.client()
    }
}

/**
 * Keeps the Wireless debugging link to the watch alive while the phone is busy with something long
 * (the build): the watch drops an idle adbd, and a connection that sat unused for 15+ minutes was
 * gone by the time the install needed it. Every [intervalMillis] a trivial command is sent; when it
 * fails the link is re-established, and a failed attempt is simply retried on the next tick.
 *
 * Never throws. Logs only short, non-secret messages through [log]. Scheduling uses [scope]'s
 * dispatcher and [now], so tests drive it with virtual time.
 */
class WatchKeepAlive(
    private val link: KeepAliveLink,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val intervalMillis: Long = INTERVAL_MS,
    private val log: (String) -> Unit = {},
) {
    private var job: Job? = null
    private var deadline: Long = Long.MAX_VALUE

    val isRunning: Boolean @Synchronized get() = job?.isActive == true

    /** Starts pinging (no-op when already running or when no watch is paired). */
    @Synchronized
    fun start() {
        if (job?.isActive == true || !link.isPaired) return
        deadline = Long.MAX_VALUE
        job = scope.launch { loop() }
    }

    /** Stops by itself [millis] from now; a running keep-alive keeps going until then. */
    @Synchronized
    fun stopAfter(millis: Long) {
        deadline = now() + millis
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        while (scope.isActive) {
            delay(intervalMillis)
            if (now() >= deadline || !link.isPaired) break
            tick()
        }
        log("keep-alive ended")
    }

    private suspend fun tick() {
        try {
            link.ping()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("keep-alive ping failed (${e.javaClass.simpleName}); reconnecting")
            try {
                link.reconnect()
                log("keep-alive reconnected")
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Throwable) {
                log("keep-alive reconnect failed (${e2.javaClass.simpleName})")
            }
        }
    }

    companion object {
        const val INTERVAL_MS = 60_000L

        /** How long the keep-alive outlives a finished build, waiting for the watch install. */
        const val AFTER_BUILD_MS = 60L * 60 * 1000

        @Volatile
        private var instance: WatchKeepAlive? = null

        /**
         * The process-wide keep-alive. It is not tied to a service: after the build it lives as long as
         * the app process does (at most [AFTER_BUILD_MS], or until the watch install finishes).
         */
        fun get(context: Context): WatchKeepAlive = instance ?: synchronized(this) {
            instance ?: WatchKeepAlive(
                AdbKeepAliveLink(WatchLink.get(context)),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                log = { Log.i("WatchKeepAlive", it) },
            ).also { instance = it }
        }
    }
}
