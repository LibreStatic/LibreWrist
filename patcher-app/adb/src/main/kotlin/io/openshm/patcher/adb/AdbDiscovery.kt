package io.openshm.patcher.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

enum class AdbServiceType(val nsdType: String) {
    /** Wireless debugging "Pair device with pairing code". */
    PAIRING("_adb-tls-pairing._tcp"),

    /** Wireless debugging connect endpoint. */
    CONNECT("_adb-tls-connect._tcp"),
}

/**
 * A resolved Wireless debugging service. [attributes] are the mDNS TXT records (for example `api`
 * and `name` on recent adbd, announced before any pairing); an older adbd sends none that matter.
 */
data class AdbService(
    val name: String,
    val host: String,
    val port: Int,
    val type: AdbServiceType,
    val attributes: Map<String, String> = emptyMap(),
)

/** Decodes mDNS TXT records. */
internal object TxtRecords {
    /** Values as UTF-8; a record without a value (null or empty) becomes the empty string. */
    fun decode(raw: Map<String, ByteArray?>?): Map<String, String> =
        raw.orEmpty().mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
}

/** Name-keyed set of resolved services; emits stable, sorted snapshots. */
internal class ServiceRegistry {
    private val services = LinkedHashMap<String, AdbService>()

    fun put(service: AdbService) {
        services[service.name] = service
    }

    fun remove(name: String) {
        services.remove(name)
    }

    fun snapshot(): List<AdbService> = services.values.sortedBy { it.name }
}

internal object HostPicker {
    /** Prefers a plain IPv4 address; link-local IPv6 would need a scope id we cannot rely on. */
    fun pick(addresses: List<InetAddress>): String? {
        val usable = addresses.filter { !it.isAnyLocalAddress }
        val chosen = usable.firstOrNull { it is Inet4Address }
            ?: usable.firstOrNull { !it.isLinkLocalAddress }
            ?: return null
        return chosen.hostAddress?.substringBefore('%')
    }
}

/**
 * mDNS discovery of Wireless debugging services through [NsdManager].
 *
 * Needs `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `INTERNET` (declared by this module).
 * Resolution: Android 14+ (API 34) uses `registerServiceInfoCallback`, which also reports address
 * changes; API 31-33 use the deprecated `resolveService`, serialised because those releases fail a
 * second concurrent resolve with `FAILURE_ALREADY_ACTIVE`.
 */
class AdbDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Emits the current list of resolved services of [type] whenever it changes. The flow fails
     * with [AdbException.Discovery] if the system refuses to start discovery. Cancelling the
     * collector stops discovery.
     */
    fun discover(type: AdbServiceType): Flow<List<AdbService>> =
        fixedServices?.let { fixed -> flow { emit(fixed.filter { it.type == type }); awaitCancellation() } } ?: discoverMdns(type)

    private fun discoverMdns(type: AdbServiceType): Flow<List<AdbService>> = callbackFlow {
        val registry = ServiceRegistry()
        val resolver: Resolver = if (Build.VERSION.SDK_INT >= 34) {
            CallbackResolver(nsd, CALLBACK_EXECUTOR)
        } else {
            QueueResolver(nsd)
        }

        fun publish() {
            synchronized(registry) { trySend(registry.snapshot()) }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                close(AdbException.Discovery("mDNS discovery failed to start (code $errorCode)"))
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.resolve(info) { host, port, attributes ->
                    synchronized(registry) { registry.put(AdbService(info.serviceName, host, port, type, attributes)) }
                    publish()
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                resolver.forget(info)
                synchronized(registry) { registry.remove(info.serviceName) }
                publish()
            }
        }

        try {
            nsd.discoverServices(type.nsdType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            close(AdbException.Discovery("mDNS discovery could not start: ${e.javaClass.simpleName}"))
        }
        publish()

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolver.close()
        }
    }.distinctUntilChanged()

    companion object {
        /**
         * Debug builds only: the services every discovery reports instead of mDNS, for an emulated watch
         * that cannot announce itself (set from `debug.openshm.watch_adb`, see `PatcherApplication`).
         * Null in normal use.
         */
        @Volatile
        var fixedServices: List<AdbService>? = null

        /**
         * Shared and never shut down: NsdManager still delivers callbacks (such as
         * onServiceInfoCallbackUnregistered) after they are unregistered, and a task rejected by a
         * terminated executor crashes the process on NsdManager's handler thread. A late callback
         * here only reaches a closed channel.
         */
        private val CALLBACK_EXECUTOR: Executor by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "adb-nsd-callbacks").apply { isDaemon = true } }
        }
    }

    private interface Resolver {
        fun resolve(info: NsdServiceInfo, onResolved: (host: String, port: Int, attributes: Map<String, String>) -> Unit)
        fun forget(info: NsdServiceInfo)
        fun close()
    }

    /** API 34+: a service-info callback per service, kept until the service is lost. */
    @android.annotation.TargetApi(34)
    private class CallbackResolver(private val nsd: NsdManager, private val executor: Executor) : Resolver {
        private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

        @Synchronized
        override fun resolve(info: NsdServiceInfo, onResolved: (String, Int, Map<String, String>) -> Unit) {
            if (callbacks.containsKey(info.serviceName)) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    synchronized(this@CallbackResolver) { callbacks.remove(info.serviceName) }
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val host = HostPicker.pick(serviceInfo.hostAddresses) ?: return
                    if (serviceInfo.port > 0) onResolved(host, serviceInfo.port, TxtRecords.decode(serviceInfo.attributes))
                }

                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks[info.serviceName] = callback
            nsd.registerServiceInfoCallback(info, executor, callback)
        }

        @Synchronized
        override fun forget(info: NsdServiceInfo) {
            callbacks.remove(info.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }

        @Synchronized
        override fun close() {
            callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            callbacks.clear()
        }
    }

    /** API 31-33: one `resolveService` at a time. */
    @Suppress("DEPRECATION")
    private class QueueResolver(private val nsd: NsdManager) : Resolver {
        private class Pending(val info: NsdServiceInfo, val onResolved: (String, Int, Map<String, String>) -> Unit)

        private val queue = ArrayDeque<Pending>()
        private var active = false
        private var closed = false

        @Synchronized
        override fun resolve(info: NsdServiceInfo, onResolved: (String, Int, Map<String, String>) -> Unit) {
            if (closed) return
            queue.addLast(Pending(info, onResolved))
            pump()
        }

        @Synchronized
        override fun forget(info: NsdServiceInfo) {
            queue.removeAll { it.info.serviceName == info.serviceName }
        }

        @Synchronized
        override fun close() {
            closed = true
            queue.clear()
        }

        @Synchronized
        private fun pump() {
            if (active || closed) return
            val next = queue.removeFirstOrNull() ?: return
            active = true
            try {
                nsd.resolveService(next.info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = finished()

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.let { HostPicker.pick(listOf(it)) }
                        if (host != null && serviceInfo.port > 0) {
                            next.onResolved(host, serviceInfo.port, TxtRecords.decode(serviceInfo.attributes))
                        }
                        finished()
                    }
                })
            } catch (e: RuntimeException) {
                finished()
            }
        }

        @Synchronized
        private fun finished() {
            active = false
            pump()
        }
    }
}
