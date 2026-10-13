package io.openshm.patcher.device

import android.content.Context
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.adb.SelfAdbSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class AdbPresenceState(val watchAvailable: Boolean = false, val phoneAvailable: Boolean = false)

/**
 * Process-wide "is the paired watch / this phone's Wireless debugging on the network right now",
 * from the `_adb-tls-connect` announcements. It only listens once the patcher was paired with a device
 * at least once ([hasEverPaired]), so a fresh install starts no mDNS. Reference-counted by owner like
 * [WatchAnnouncements]. A device that stops announcing is only reported gone after [HOLD_MS].
 *
 * [WatchAnnouncements.state] is deliberately not consulted: it keeps the last watch it ever saw, so it
 * cannot say whether the watch is there now and would make the watch look present forever.
 */
object AdbPresence {
    const val HOLD_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AdbPresenceState())
    val state: StateFlow<AdbPresenceState> = _state.asStateFlow()

    private val holders = mutableSetOf<String>()
    private var job: Job? = null

    /** [owner] needs the presence listener. Idempotent per owner; does nothing before the first pairing. */
    fun start(context: Context, owner: String) {
        val app = context.applicationContext
        if (!hasEverPaired(app)) return
        val link = WatchLink.get(app)
        start(
            owner = owner,
            services = { AdbDiscovery(app).discover(AdbServiceType.CONNECT) },
            rememberedHost = { link.rememberedHost },
            serialPrefix = { link.rememberedSerialPrefix },
            isOwnAddress = SelfAdbSession.Companion::isOwnAddress,
        )
    }

    /** [start] without Android. */
    @Synchronized
    internal fun start(
        owner: String,
        services: () -> Flow<List<AdbService>>,
        rememberedHost: () -> String?,
        serialPrefix: () -> String?,
        isOwnAddress: (String) -> Boolean,
    ) {
        holders += owner
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                services()
                    .map { match(it, rememberedHost(), serialPrefix(), isOwnAddress) }
                    .holdFalse(HOLD_MS)
                    .collect { _state.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent: nothing is reported as available.
                _state.value = AdbPresenceState()
            }
        }
    }

    /** [owner] no longer needs the listener; it stops once nobody does. */
    @Synchronized
    fun stop(owner: String) {
        if (!holders.remove(owner) || holders.isNotEmpty()) return
        job?.cancel()
        job = null
        _state.value = AdbPresenceState()
    }

    /** True once the watch was paired, or this phone's own Wireless debugging trusted the app. */
    fun hasEverPaired(context: Context): Boolean =
        WatchLink.get(context).rememberedHost != null || SelfAdbTrust.isTrusted(context)

    /** Forgets everything, for tests. */
    @Synchronized
    internal fun reset() {
        holders.clear()
        job?.cancel()
        job = null
        _state.value = AdbPresenceState()
    }

    /**
     * What one list of announced connect services says. The watch is a service that is not on this phone
     * and either sits at the remembered host or carries the remembered `adb-<serial>-` name prefix (the
     * watch got a new address); the phone is a service on one of its own addresses.
     */
    internal fun match(
        services: List<AdbService>,
        rememberedHost: String?,
        serialPrefix: String?,
        isOwnAddress: (String) -> Boolean,
    ): AdbPresenceState {
        val own = services.filter { isOwnAddress(it.host) }
        val others = services.filter { !isOwnAddress(it.host) }
        val watch = others.any { service ->
            (rememberedHost != null && service.host == rememberedHost) ||
                (serialPrefix != null && service.name.startsWith(serialPrefix))
        }
        return AdbPresenceState(watchAvailable = watch, phoneAvailable = own.isNotEmpty())
    }
}

/** Turning on is immediate; turning off only after [holdMillis] without the device coming back. */
internal fun Flow<AdbPresenceState>.holdFalse(holdMillis: Long): Flow<AdbPresenceState> {
    val upstream = this
    return channelFlow {
        var current = AdbPresenceState()
        upstream.collectLatest { next ->
            val raised = AdbPresenceState(current.watchAvailable || next.watchAvailable, current.phoneAvailable || next.phoneAvailable)
            if (raised != current) {
                current = raised
                send(current)
            }
            if (current != next) {
                delay(holdMillis)
                current = next
                send(current)
            }
        }
    }
}
