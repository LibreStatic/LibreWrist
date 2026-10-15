package io.openshm.patcher.device

import android.content.Context
import android.util.Log
import io.openshm.patcher.adb.KeepAwakeHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The two steps of keeping the watch awake; the real one runs a helper process on the watch. */
interface AwakeHolder {
    /** Puts the helper on the watch. May be called again after a failure. */
    suspend fun prepare()

    /** Holds the wakelock until shortly before the helper's own limit; returns normally then. Cancel to release. */
    suspend fun holdOnce()
}

/** [AwakeHolder] over a [WatchLink]: the client is fetched again on every call so a reconnect is picked up. */
class HelperAwakeHolder(private val link: WatchLink, private val dex: () -> ByteArray) : AwakeHolder {
    override suspend fun prepare() {
        val bytes = withContext(Dispatchers.IO) { dex() }
        KeepAwakeHelper(link.client()).install(bytes)
    }

    override suspend fun holdOnce() {
        KeepAwakeHelper(link.client()).hold(HELPER_MAX_MS, HEARTBEAT_MS, HEARTBEAT_TIMEOUT_MS)
    }

    companion object {
        const val HELPER_MAX_MS = 15 * 60_000L
        const val HEARTBEAT_MS = 10_000L
        const val HEARTBEAT_TIMEOUT_MS = 45_000L
    }
}

/**
 * Keeps the watch awake while a block runs (the watch dozes and slows installs over Wireless
 * debugging). It is best effort and never changes the block's outcome or delays it: every failure of
 * the [holder] is logged and retried, and supervision stops for good after [sessionCapMillis]. The
 * wakelock itself lives in a helper process on the watch that ends on its own when we stop.
 */
class WatchAwake(
    private val holder: AwakeHolder,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionCapMillis: Long = SESSION_CAP_MS,
    private val retryDelayMillis: Long = RETRY_DELAY_MS,
) {
    suspend fun <T> around(block: suspend () -> T): T = coroutineScope {
        val job = launch { supervise() }
        try {
            block()
        } finally {
            job.cancel()
        }
    }

    private suspend fun supervise() {
        val start = now()
        var prepared = false
        var prepareFailures = 0
        while (currentCoroutineContext().isActive) {
            if (now() - start >= sessionCapMillis) {
                log("session cap reached, no longer keeping the watch awake")
                return
            }
            val attemptStart = now()
            try {
                if (!prepared) {
                    holder.prepare()
                    prepared = true
                }
                holder.holdOnce()
                // A normal return means "renew now"; guard against a holder that returns at once.
                if (now() - attemptStart < MIN_HOLD_MS) delay(retryDelayMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("keep-awake failed: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
                if (!prepared && ++prepareFailures >= MAX_PREPARE_FAILURES) {
                    log("giving up on keeping the watch awake")
                    return
                }
                delay(retryDelayMillis)
            }
        }
    }

    companion object {
        const val SESSION_CAP_MS = 60 * 60_000L
        const val RETRY_DELAY_MS = 5_000L
        private const val MAX_PREPARE_FAILURES = 3
        private const val MIN_HOLD_MS = 1_000L
        private const val ASSET = "owncode/tools/keep-awake.dex"

        /** The real thing for [link], with the helper read from the app's assets on first use. */
        fun forWatch(context: Context, link: WatchLink): WatchAwake {
            val app = context.applicationContext
            val holder = HelperAwakeHolder(link) { app.assets.open(ASSET).use { it.readBytes() } }
            return WatchAwake(holder, log = { Log.i("WatchAwake", it) })
        }
    }
}
