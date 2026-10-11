package io.openshm.patcher.adb

import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Progress of pairing the phone with its own Wireless debugging ("self-ADB"). */
sealed interface SelfAdbState {
    data object Idle : SelfAdbState

    /**
     * Trying the key this app already has against this device's own connect service, before
     * asking for a pairing code: if Wireless debugging still trusts the key, no code is needed.
     */
    data object Reconnecting : SelfAdbState
    data object WaitingForPairingService : SelfAdbState

    /**
     * More than one pairing service on this device's own addresses; the session refuses to guess.
     * It recovers by itself if the list shrinks to one (for example a stale announcement expires).
     */
    data object AmbiguousService : SelfAdbState

    /** The user must type the six-digit code shown by "Pair device with pairing code". */
    data class WaitingForCode(val port: Int, val lastFailure: PairingFailure? = null) : SelfAdbState
    data object Pairing : SelfAdbState
    data object WaitingForConnectService : SelfAdbState
    data object Connecting : SelfAdbState
    data class Connected(val client: AdbClient) : SelfAdbState
    data class Failed(val error: AdbException) : SelfAdbState
}

/**
 * The self-ADB state machine, free of Android UI so it can be tested with fakes. It pairs over
 * [localHost] with the pairing port found by mDNS, then connects to the connect service port.
 * [SelfAdbPairingNotifier] drives it from a notification.
 *
 * Only services advertised from one of this device's own addresses are considered ([isLocalHost]);
 * a watch on the same Wi-Fi advertises the same service types and must never be dialled on its
 * port. If several local pairing services remain, the session goes to
 * [SelfAdbState.AmbiguousService] instead of choosing by name or order, since the code must only
 * reach the dialog the user is looking at. Several local connect services are all dialled in turn:
 * they can only be this device's own adbd, and stale ones refuse the connection.
 */
class SelfAdbSession(
    private val backend: AdbBackend,
    private val pairingServices: Flow<List<AdbService>>,
    private val connectServices: Flow<List<AdbService>>,
    private val scope: CoroutineScope,
    private val localHost: String = "127.0.0.1",
    private val connectServiceTimeoutMillis: Long = 15_000,
    private val reconnectTimeoutMillis: Long = 5_000,
    private val isLocalHost: (String) -> Boolean = ::isOwnAddress,
) {
    private val mutableState = MutableStateFlow<SelfAdbState>(SelfAdbState.Idle)
    val state: StateFlow<SelfAdbState> = mutableState

    private var discoveryJob: Job? = null
    private var submitJob: Job? = null

    /** Bumped by every start/submit/stop; a stale coroutine's transitions are dropped. */
    private var generation = 0

    @Volatile
    private var pairingPort: Int? = null

    /**
     * Tries to connect with the already-paired key first ([SelfAdbState.Reconnecting]); when that
     * finds no connect service or is rejected, starts looking for the pairing service. Safe to call
     * again after a failure.
     */
    @Synchronized
    fun start() {
        discoveryJob?.cancel()
        submitJob?.cancel()
        val gen = ++generation
        pairingPort = null
        mutableState.value = SelfAdbState.Reconnecting
        discoveryJob = scope.launch {
            val client = try {
                reconnect()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbException) {
                null // not paired (the key was rejected): fall back to the pairing flow
            }
            if (client != null) {
                // A late result must never replace a newer state, and must not leak the connection.
                if (!transition(gen, SelfAdbState.Connected(client))) runCatching { client.close() }
                return@launch
            }
            if (!transition(gen, SelfAdbState.WaitingForPairingService)) return@launch
            try {
                pairingServices.collect { services -> onPairingServices(services.filter { isLocalHost(it.host) }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbException) {
                mutableState.value = SelfAdbState.Failed(e)
            }
        }
    }

    /**
     * Dials the local connect services announced within [reconnectTimeoutMillis] with the stored
     * key. A refused port (stale announcement) moves on; any other [AdbException] means the key is
     * not trusted and propagates. Null when no local connect service shows up.
     */
    private suspend fun reconnect(): AdbClient? {
        val tried = HashSet<Int>()
        var client: AdbClient? = null
        withTimeoutOrNull(reconnectTimeoutMillis) {
            connectServices.first { list ->
                for (port in list.filter { isLocalHost(it.host) }.map { it.port }.distinct()) {
                    if (!tried.add(port)) continue
                    try {
                        client = AdbClient.connect(backend, localHost, port)
                        return@first true
                    } catch (e: AdbException.ConnectionFailed) {
                        if (!AdbErrors.isRefused(e)) throw e
                    }
                }
                false
            }
        }
        return client
    }

    @Synchronized
    private fun onPairingServices(local: List<AdbService>) {
        val current = mutableState.value
        val waiting = current is SelfAdbState.WaitingForPairingService ||
            current is SelfAdbState.AmbiguousService || current is SelfAdbState.WaitingForCode
        if (!waiting) return
        when {
            local.size > 1 -> {
                pairingPort = null
                mutableState.value = SelfAdbState.AmbiguousService
            }
            local.isEmpty() -> {
                pairingPort = null
                mutableState.value = SelfAdbState.WaitingForPairingService
            }
            else -> {
                val port = local.single().port
                pairingPort = port
                mutableState.value =
                    if (current is SelfAdbState.WaitingForCode) current.copy(port = port) else SelfAdbState.WaitingForCode(port)
            }
        }
    }

    /** Pairs with [code] and connects. Ignored unless a code is currently being waited for. */
    @Synchronized
    fun submitCode(code: String): Job? {
        if (mutableState.value !is SelfAdbState.WaitingForCode) return null
        val port = pairingPort ?: return null
        submitJob?.cancel()
        val gen = ++generation
        mutableState.value = SelfAdbState.Pairing // synchronously: a second submit is ignored
        val job = scope.launch { pairAndConnect(gen, port, code.trim()) }
        submitJob = job
        return job
    }

    @Synchronized
    fun stop() {
        generation++
        discoveryJob?.cancel()
        submitJob?.cancel()
        (mutableState.value as? SelfAdbState.Connected)?.let { runCatching { it.client.close() } }
        mutableState.value = SelfAdbState.Idle
    }

    /** Sets [next] only if [gen] is still current. */
    @Synchronized
    private fun transition(gen: Int, next: SelfAdbState): Boolean {
        if (gen != generation) return false
        mutableState.value = next
        return true
    }

    private suspend fun pairAndConnect(gen: Int, port: Int, code: String) {
        try {
            AdbPairing(backend).pair(localHost, port, code)
        } catch (e: AdbException.Pairing) {
            transition(
                gen,
                when (e.reason) {
                    PairingFailure.INVALID_CODE_FORMAT, PairingFailure.WRONG_CODE ->
                        SelfAdbState.WaitingForCode(port, e.reason)
                    else -> SelfAdbState.Failed(e)
                },
            )
            return
        } catch (e: AdbException) {
            transition(gen, SelfAdbState.Failed(e))
            return
        }
        if (!transition(gen, SelfAdbState.WaitingForConnectService)) return
        val client = try {
            connectToLocalService(gen)
        } catch (e: AdbException) {
            transition(gen, SelfAdbState.Failed(e))
            return
        } ?: return
        // A late result must never replace a newer state, and must not leak the connection.
        if (!transition(gen, SelfAdbState.Connected(client))) runCatching { client.close() }
    }

    /**
     * Dials every local connect port as it is announced until one accepts. NsdManager keeps
     * announcements of earlier Wireless debugging sessions next to the live one (seen on Android
     * 16: the same instance name at the old port plus a "(2)" copy at the new one), and the stale
     * port refuses the connection at once. Only a refused port moves on to the next candidate; any
     * other error is final. Returns null when [gen] went stale.
     */
    private suspend fun connectToLocalService(gen: Int): AdbClient? {
        val tried = HashSet<Int>()
        var refused: AdbException? = null
        var client: AdbClient? = null
        var superseded = false
        val done = withTimeoutOrNull(connectServiceTimeoutMillis) {
            connectServices.first { list ->
                for (port in list.filter { isLocalHost(it.host) }.map { it.port }.distinct()) {
                    if (!tried.add(port)) continue
                    if (!transition(gen, SelfAdbState.Connecting)) {
                        superseded = true
                        return@first true
                    }
                    try {
                        client = AdbClient.connect(backend, localHost, port)
                        return@first true
                    } catch (e: AdbException.ConnectionFailed) {
                        if (!AdbErrors.isRefused(e)) throw e
                        refused = e
                    }
                }
                if (tried.isNotEmpty() && !transition(gen, SelfAdbState.WaitingForConnectService)) {
                    superseded = true
                    return@first true
                }
                false
            }
        }
        if (superseded) return null
        if (done == null) {
            throw refused ?: AdbException.Discovery("The Wireless debugging connect service was not found")
        }
        return client
    }

    companion object {
        /** True for loopback and for addresses assigned to one of this device's interfaces. */
        fun isOwnAddress(host: String): Boolean = try {
            val address = InetAddress.getByName(host)
            address.isLoopbackAddress || NetworkInterface.getByInetAddress(address) != null
        } catch (e: Exception) {
            false
        }
    }
}
