package io.openshm.patcher.adb

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
 */
class SelfAdbSession(
    private val backend: AdbBackend,
    private val pairingServices: Flow<List<AdbService>>,
    private val connectServices: Flow<List<AdbService>>,
    private val scope: CoroutineScope,
    private val localHost: String = "127.0.0.1",
    private val connectServiceTimeoutMillis: Long = 15_000,
) {
    private val mutableState = MutableStateFlow<SelfAdbState>(SelfAdbState.Idle)
    val state: StateFlow<SelfAdbState> = mutableState

    private var discoveryJob: Job? = null
    private var submitJob: Job? = null

    @Volatile
    private var pairingPort: Int? = null

    /** Starts looking for the pairing service. Safe to call again after a failure. */
    @Synchronized
    fun start() {
        discoveryJob?.cancel()
        pairingPort = null
        mutableState.value = SelfAdbState.WaitingForPairingService
        discoveryJob = scope.launch {
            try {
                pairingServices.collect { services ->
                    val port = services.firstOrNull()?.port
                    pairingPort = port
                    val current = mutableState.value
                    if (port != null && current is SelfAdbState.WaitingForPairingService) {
                        mutableState.value = SelfAdbState.WaitingForCode(port)
                    } else if (port != null && current is SelfAdbState.WaitingForCode && current.port != port) {
                        mutableState.value = current.copy(port = port)
                    } else if (port == null && current is SelfAdbState.WaitingForCode) {
                        mutableState.value = SelfAdbState.WaitingForPairingService
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbException) {
                mutableState.value = SelfAdbState.Failed(e)
            }
        }
    }

    /** Pairs with [code] and connects. Ignored unless a code is currently being waited for. */
    @Synchronized
    fun submitCode(code: String): Job? {
        if (mutableState.value !is SelfAdbState.WaitingForCode) return null
        val port = pairingPort ?: return null
        val job = scope.launch { pairAndConnect(port, code.trim()) }
        submitJob = job
        return job
    }

    @Synchronized
    fun stop() {
        discoveryJob?.cancel()
        submitJob?.cancel()
        (mutableState.value as? SelfAdbState.Connected)?.let { runCatching { it.client.close() } }
        mutableState.value = SelfAdbState.Idle
    }

    private suspend fun pairAndConnect(port: Int, code: String) {
        mutableState.value = SelfAdbState.Pairing
        try {
            AdbPairing(backend).pair(localHost, port, code)
        } catch (e: AdbException.Pairing) {
            mutableState.value = when (e.reason) {
                PairingFailure.INVALID_CODE_FORMAT, PairingFailure.WRONG_CODE ->
                    SelfAdbState.WaitingForCode(port, e.reason)
                else -> SelfAdbState.Failed(e)
            }
            return
        }
        mutableState.value = SelfAdbState.WaitingForConnectService
        val connect = withTimeoutOrNull(connectServiceTimeoutMillis) {
            connectServices.first { it.isNotEmpty() }.first()
        }
        if (connect == null) {
            mutableState.value =
                SelfAdbState.Failed(AdbException.Discovery("The Wireless debugging connect service was not found"))
            return
        }
        mutableState.value = SelfAdbState.Connecting
        mutableState.value = try {
            SelfAdbState.Connected(AdbClient.connect(backend, localHost, connect.port))
        } catch (e: AdbException) {
            SelfAdbState.Failed(e)
        }
    }
}
