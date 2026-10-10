package io.openshm.patcher.device

import android.content.Context
import io.openshm.patcher.adb.AdbBackend
import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbKeyStore
import io.openshm.patcher.adb.AdbPairing
import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.adb.LibAdbBackend
import java.net.ConnectException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Where the paired watch's host is remembered (a plain IP address, not a secret). */
interface WatchHostStore {
    var host: String?
}

/**
 * The phone's ADB link to the watch: pairs once, remembers the watch's host, and keeps one live
 * [AdbClient], reconnecting through mDNS when it dropped. The connect port changes every time
 * Wireless debugging is toggled, and NsdManager keeps stale announcements, so every announced port
 * of the remembered host is tried and only refused ones are skipped.
 *
 * Android-free (backend, discovery and storage are injected) so JVM tests can drive it with fakes;
 * [WatchLink.get] wires the real ones.
 */
open class WatchLink(
    private val backend: AdbBackend,
    private val connectServices: () -> Flow<List<AdbService>>,
    private val store: WatchHostStore,
    private val serviceTimeoutMillis: Long = SERVICE_TIMEOUT_MS,
) {
    private val mutex = Mutex()
    private var current: AdbClient? = null

    val rememberedHost: String? get() = store.host

    /**
     * Pairs with the watch's pairing service at [host]:[port], remembers [host], and connects. A
     * failed connect afterwards is not an error here (the code is already used up); [client]
     * retries it when the first install needs the link.
     */
    open suspend fun pair(host: String, port: Int, code: String) {
        mutex.withLock {
            AdbPairing(backend).pair(host, port, code)
            closeCurrent()
            store.host = host
            try {
                current = connectToRemembered(host)
            } catch (e: AdbException) {
                current = null
            }
        }
    }

    /** The live client, or a fresh connection to the remembered watch. */
    open suspend fun client(): AdbClient = mutex.withLock {
        current?.takeIf { it.isConnected }?.let { return it }
        closeCurrent()
        val host = store.host ?: throw AdbException.PairingRequired()
        connectToRemembered(host).also { current = it }
    }

    /** Drops the connection (the remembered host stays). */
    open fun forget() {
        closeCurrent()
    }

    @Synchronized
    private fun closeCurrent() {
        current?.let { runCatching { it.close() } }
        current = null
    }

    private suspend fun connectToRemembered(host: String): AdbClient {
        val tried = HashSet<Int>()
        var refused: AdbException? = null
        var client: AdbClient? = null
        val found = withTimeoutOrNull(serviceTimeoutMillis) {
            connectServices().firstOrNull { list ->
                for (port in list.filter { it.host == host }.map { it.port }.distinct()) {
                    if (!tried.add(port)) continue
                    try {
                        client = AdbClient.connect(backend, host, port)
                        return@firstOrNull true
                    } catch (e: AdbException.ConnectionFailed) {
                        if (!isRefused(e)) throw e
                        refused = e
                    }
                }
                false
            }
        }
        if (found == null) {
            throw refused ?: AdbException.Discovery("The watch's Wireless debugging service was not found")
        }
        return checkNotNull(client)
    }

    companion object {
        const val SERVICE_TIMEOUT_MS = 20_000L
        private const val PREFS = "watch"
        private const val KEY_HOST = "host"

        @Volatile
        private var instance: WatchLink? = null

        fun get(context: Context): WatchLink = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): WatchLink {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val store = object : WatchHostStore {
                override var host: String?
                    get() = prefs.getString(KEY_HOST, null)
                    set(value) = prefs.edit().putString(KEY_HOST, value).apply()
            }
            val discovery = AdbDiscovery(context)
            return WatchLink(
                LibAdbBackend(AdbKeyStore.forContext(context)),
                { discovery.discover(AdbServiceType.CONNECT) },
                store,
            )
        }

        /** True when the cause chain is a refused TCP connection (nothing listens on the port). */
        internal fun isRefused(t: Throwable): Boolean =
            generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8).any { it is ConnectException }
    }
}
