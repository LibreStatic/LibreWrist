package io.openshm.patcher.ui.screens.watchpair

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.adb.PairingFailure
import io.openshm.patcher.adb.SelfAdbSession
import io.openshm.patcher.device.WatchLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pure reducer so it can be unit tested without a ViewModel. */
internal fun WatchPairUiState.reduce(event: WatchPairEvent): WatchPairUiState = when (event) {
    is WatchPairEvent.CodeChanged -> copy(code = event.code.filter(Char::isDigit).take(PAIRING_CODE_LENGTH), errorRes = null)
    is WatchPairEvent.AddressChanged -> copy(
        foundAddress = event.address.ifBlank { null },
        errorRes = if (event.address.isBlank() || parseAddress(event.address) != null) null else R.string.watchpair_error_bad_address,
    )
    WatchPairEvent.ToggleEditAddress -> copy(editingAddress = !editingAddress)
    WatchPairEvent.Pair -> if (canPair) copy(pairing = true, errorRes = null) else this
}

private val ADDRESS = Regex("([A-Za-z0-9][A-Za-z0-9.:-]*):(\\d{1,5})")

/** Splits `host:port`; null when it is not a host with a port in 1..65535. */
internal fun parseAddress(address: String): Pair<String, Int>? {
    val match = ADDRESS.matchEntire(address.trim()) ?: return null
    val port = match.groupValues[2].toInt()
    return if (port in 1..65535) match.groupValues[1] to port else null
}

/** The pairing service of another device on the network: the watch. This phone's own one is ignored. */
internal fun foundWatchAddress(services: List<AdbService>, isOwn: (String) -> Boolean): String? =
    services.firstOrNull { !isOwn(it.host) }?.let { "${it.host}:${it.port}" }

@StringRes
internal fun pairErrorRes(e: Throwable): Int = when (e) {
    is AdbException.Pairing -> when (e.reason) {
        PairingFailure.INVALID_CODE_FORMAT, PairingFailure.WRONG_CODE -> R.string.watchpair_error_wrong_code
        PairingFailure.TIMEOUT -> R.string.watchpair_error_timeout
        PairingFailure.NETWORK -> R.string.watchpair_error_network
        PairingFailure.UNKNOWN -> R.string.watchpair_error_other
    }
    is AdbException.Timeout -> R.string.watchpair_error_timeout
    is AdbException.ConnectionFailed -> R.string.watchpair_error_network
    is AdbException.RestartRequired -> R.string.watchpair_error_restart
    else -> R.string.watchpair_error_other
}

/** Finds the watch's pairing service by mDNS and pairs through [WatchLink]. */
class WatchPairViewModel(application: Application) : AndroidViewModel(application) {
    private val link = WatchLink.get(application)
    private val _state = MutableStateFlow(WatchPairUiState())
    val state: StateFlow<WatchPairUiState> = _state.asStateFlow()

    /** Once the user typed an address, discovery no longer replaces it. */
    @Volatile
    private var addressEdited = false

    init {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                AdbDiscovery(application).discover(AdbServiceType.PAIRING).collect { services ->
                    val found = foundWatchAddress(services, SelfAdbSession::isOwnAddress)
                    if (!addressEdited) _state.update { if (it.pairing) it else it.copy(foundAddress = found) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No discovery: the address can still be typed in.
            }
        }
    }

    fun onEvent(event: WatchPairEvent) {
        if (event is WatchPairEvent.AddressChanged) addressEdited = true
        val wasPairing = _state.value.pairing
        _state.update { it.reduce(event) }
        if (!wasPairing && _state.value.pairing) pair()
    }

    /** The screen moved on; coming back starts a fresh pairing. */
    fun onNavigated() = _state.update { it.copy(paired = false, code = "") }

    private fun pair() {
        val snapshot = _state.value
        val (host, port) = parseAddress(snapshot.foundAddress.orEmpty()) ?: run {
            _state.update { it.copy(pairing = false, errorRes = R.string.watchpair_error_bad_address) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                link.pair(host, port, snapshot.code)
                _state.update { it.copy(pairing = false, paired = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(pairing = false, errorRes = pairErrorRes(e)) }
            }
        }
    }
}
