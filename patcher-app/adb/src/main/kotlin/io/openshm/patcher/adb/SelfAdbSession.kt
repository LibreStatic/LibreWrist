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
    data object WaitingForPairingService : SelfAdbState

    /**
     * More than one service on this device's own addresses; the session refuses to guess. It
     * recovers by itself if the list shrinks to one (for example a stale announcement expires).
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
 * port. If several local services remain, the session goes to [SelfAdbState.AmbiguousService]
 * instead of choosing by name or order.
 */
class SelfAdbSession(
    private val backend: AdbBackend,
    private val pairingServices: Flow<List<AdbService>>,
    private val connectServices: Flow<List<AdbService>>,
    private val scope: CoroutineScope,
    private val localHost: String = "127.0.0.1",
    private val connectServiceTimeoutMillis: Long = 15_000,
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

    /** Starts looking for the pairing service. Safe to call again after a failure. */
    @Synchronized
    fun start() {
        discoveryJob?.cancel()
        submitJob?.cancel()
        generation++
        pairingPort = null
        mutableState.value = SelfAdbState.WaitingForPairingService
        discoveryJob = scope.launch {
            try {
                pairingServices.collect { services -> onPairingServices(services.filter { isLocalHost(it.host) }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbException) {
                mutableState.value = SelfAdbState.Failed(e)
            }
        }
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
        val candidates = withTimeoutOrNull(connectServiceTimeoutMillis) {
            connectServices.first { list -> list.any { isLocalHost(it.host) } }.filter { isLocalHost(it.host) }
        }
        if (candidates == null) {
            transition(
                gen,
                SelfAdbState.Failed(AdbException.Discovery("The Wireless debugging connect service was not found")),
            )
            return
        }
        if (candidates.size > 1) {
            transition(gen, SelfAdbState.AmbiguousService)
            return
        }
        if (!transition(gen, SelfAdbState.Connecting)) return
        val client = try {
            AdbClient.connect(backend, localHost, candidates.single().port)
        } catch (e: AdbException) {
            transition(gen, SelfAdbState.Failed(e))
            return
        }
        // A late result must never replace a newer state, and must not leak the connection.
        if (!transition(gen, SelfAdbState.Connected(client))) runCatching { client.close() }
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
