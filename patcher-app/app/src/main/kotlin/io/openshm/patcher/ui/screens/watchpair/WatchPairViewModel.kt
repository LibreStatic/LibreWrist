package io.openshm.patcher.ui.screens.watchpair

import android.app.Application
import android.util.Log
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
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.WatchDetector
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.ui.components.PAIRING_CODE_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pure reducer so it can be unit tested without a ViewModel. */
internal fun WatchPairUiState.reduce(event: WatchPairEvent): WatchPairUiState = when (event) {
    is WatchPairEvent.CodeChanged -> if (pairing) {
        this
    } else {
        val next = copy(code = event.code.filter(Char::isDigit).take(PAIRING_CODE_LENGTH), errorRes = null)
        // Only the edit that completes the code starts pairing; a repeated or longer value does not.
        if (!codeComplete && next.canPair) next.copy(pairing = true) else next
    }
    is WatchPairEvent.HostChanged -> copy(host = event.host.trim(), errorRes = null)
    is WatchPairEvent.PortChanged -> copy(port = event.port.filter(Char::isDigit).take(5), errorRes = null)
    // The first time the watch shows up the guide has done its job and closes by itself.
    is WatchPairEvent.Discovered -> when {
        pairing -> this
        else -> copy(foundAddress = event.address, guideOpen = if (foundAddress == null && event.address != null) false else guideOpen)
    }
    is WatchPairEvent.GuideStep -> copy(guideStep = event.step.coerceIn(0, WATCH_GUIDE_STEPS - 1))
    WatchPairEvent.ShowGuide -> copy(guideOpen = true)
    WatchPairEvent.HideGuide -> copy(guideOpen = false)
    WatchPairEvent.ToggleManual -> copy(manual = !manual, errorRes = null)
    WatchPairEvent.ConfirmCorrection -> copy(correction = null, paired = true)
    WatchPairEvent.ReconnectStarted -> if (pairing || paired || reconnected) this else copy(reconnecting = true)
    is WatchPairEvent.Reconnected ->
        // A code pairing in progress wins; its own outcome is reported by pair().
        if (pairing) copy(reconnecting = false) else copy(
            reconnecting = false,
            rememberedNotFound = false,
            reconnected = true,
            reconnectedModel = event.model,
            paired = event.correction == null,
            correction = event.correction,
            errorRes = null,
        )
    is WatchPairEvent.ReconnectFailed -> copy(reconnecting = false, rememberedNotFound = event.notFound || rememberedNotFound)
    // The code field is read-only from this very state on, not after the first answer.
    WatchPairEvent.Pair -> if (canPair) copy(pairing = true, errorRes = null) else this
}

private const val DETECT_TIMEOUT_MS = 5_000L

private val ADDRESS = Regex("([A-Za-z0-9][A-Za-z0-9.:-]*):(\\d{1,5})")

/** Splits `host:port`; null when it is not a host with a port in 1..65535. */
internal fun parseAddress(address: String): Pair<String, Int>? {
    val match = ADDRESS.matchEntire(address.trim()) ?: return null
    val port = match.groupValues[2].toInt()
    return if (port in 1..65535) match.groupValues[1] to port else null
}

/**
 * The connect services worth a reconnect that were not tried yet, as `host:port` keys: those of the
 * remembered [host] or, when it moved, of the recorded [serialPrefix] (see [WatchLink.candidates]).
 */
internal fun freshReconnectTargets(
    services: List<AdbService>,
    host: String,
    serialPrefix: String?,
    tried: Set<String>,
): List<String> = WatchLink.candidates(services, host, serialPrefix).map { "${it.host}:${it.port}" }.distinct().filter { it !in tried }

/** The model the remembered watch announces in its connect service (`name` TXT record), or null. */
internal fun announcedModel(services: List<AdbService>, host: String, serialPrefix: String?): String? =
    WatchLink.candidates(services, host, serialPrefix).firstNotNullOfOrNull { it.attributes["name"]?.trim()?.takeIf(String::isNotEmpty) }

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

    /** Latest connect-service snapshot and the targets already tried, for the quiet reconnect retries. */
    @Volatile
    private var latestConnect: List<AdbService> = emptyList()
    private val tried = HashSet<String>()
    private var attempt: Job? = null

    @Volatile
    private var attempting = false
    private var connectWatch: Job? = null

    @Volatile
    private var closed = false

    init {
        if (link.rememberedHost != null) startReconnect(application)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                AdbDiscovery(application).discover(AdbServiceType.PAIRING).collect { services ->
                    val found = foundWatchAddress(services, SelfAdbSession::isOwnAddress)
                    _state.update { it.reduce(WatchPairEvent.Discovered(found)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No discovery: the address can still be typed in ("Skip and do it manually").
            }
        }
    }

    fun onEvent(event: WatchPairEvent) {
        val wasPairing = _state.value.pairing
        _state.update { it.reduce(event) }
        if (!wasPairing && _state.value.pairing) pair()
    }

    /** The screen moved on; coming back starts a fresh pairing. */
    fun onNavigated() {
        closed = true
        attempt?.cancel()
        connectWatch?.cancel()
        _state.update { it.copy(paired = false, code = "", correction = null, reconnecting = false, reconnected = false) }
    }

    /**
     * Reuses the pairing the watch already trusts: one reconnect right away, then a quiet one for each
     * new connect port the remembered watch announces (turning Wireless debugging on is enough).
     * Pairing discovery keeps running meanwhile, so a code still works.
     */
    private fun startReconnect(application: Application) {
        launchAttempt(quiet = false)
        connectWatch = viewModelScope.launch(Dispatchers.Default) {
            try {
                AdbDiscovery(application).discover(AdbServiceType.CONNECT).collect { services ->
                    latestConnect = services
                    retryIfFresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No connect discovery: the first attempt and the code pairing still work.
            }
        }
    }

    @Synchronized
    private fun retryIfFresh() {
        val host = link.rememberedHost ?: return
        if (closed || attempting || _state.value.let { it.paired || it.reconnected || it.pairing }) return
        if (freshReconnectTargets(latestConnect, host, link.rememberedSerialPrefix, tried).isNotEmpty()) launchAttempt(quiet = true)
    }

    @Synchronized
    private fun launchAttempt(quiet: Boolean) {
        val host = link.rememberedHost ?: return
        tried += freshReconnectTargets(latestConnect, host, link.rememberedSerialPrefix, tried)
        if (!quiet) _state.update { it.reduce(WatchPairEvent.ReconnectStarted) }
        attempting = true
        attempt = viewModelScope.launch(Dispatchers.IO) {
            try {
                link.client()
                val correction = detectCorrection()
                val model = announcedModel(latestConnect, link.rememberedHost ?: host, link.rememberedSerialPrefix)
                Log.d("WatchPair", "reconnected with the remembered pairing")
                attempting = false
                _state.update { it.reduce(WatchPairEvent.Reconnected(model, correction)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not found, refused, or the watch forgot the key: the normal pairing UI stays.
                Log.d("WatchPair", "reconnect failed: ${e::class.simpleName}")
                attempting = false
                _state.update { it.reduce(WatchPairEvent.ReconnectFailed(notFound = e is AdbException.Discovery && !quiet)) }
                retryIfFresh()
            }
        }
    }

    /** Asks the watch for its Android version and corrects step 1's profile when it disagrees. */
    private suspend fun detectCorrection(): WatchProfileCorrection? {
        // The watch knows its Android version: correct step 1 when it disagrees, after a confirmation.
        val detected = WatchDetector.detect(link, DETECT_TIMEOUT_MS)
        val correction = WatchDetector.correction(SetupSession.state.value.watchProfile, detected) ?: return null
        SetupSession.update { it.copy(watchProfile = correction.profile) }
        return WatchProfileCorrection(correction.profile, correction.versionLabel)
    }

    private fun pair() {
        val snapshot = _state.value
        val (host, port) = parseAddress(snapshot.address.orEmpty()) ?: run {
            _state.update { it.copy(pairing = false, errorRes = R.string.watchpair_error_bad_address) }
            return
        }
        Log.d("WatchPair", "pairing attempt")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                link.pair(host, port, snapshot.code)
                val correction = detectCorrection()
                _state.update {
                    if (correction == null) it.copy(pairing = false, paired = true) else it.copy(pairing = false, correction = correction)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = pairErrorRes(e)
                // A wrong code is cleared for a new one; after a network or timeout failure the same code may be retried.
                _state.update {
                    it.copy(
                        pairing = false,
                        errorRes = error,
                        code = if (error == R.string.watchpair_error_wrong_code) "" else it.code,
                        failures = it.failures + 1,
                    )
                }
            }
        }
    }
}
