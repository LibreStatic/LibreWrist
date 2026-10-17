package io.openshm.patcher.device.guide

import io.openshm.patcher.device.SettingsTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which Settings walk-through a hand-off asks for; the service turns it into a [GuideScript]. */
sealed interface GuideRequest {
    /** Developer options → Wireless debugging → pair with a code (unhiding developer options first if needed). */
    data object WirelessDebugging : GuideRequest

    /**
     * One permission page of another app ([SettingsTarget.AppDetails] has no guide). [pkg] is that app:
     * some pages open on the list of all apps, and Tic opens its row first.
     */
    data class Permission(val target: SettingsTarget, val pkg: String? = null) : GuideRequest

    /** "Install unknown apps" for this app. */
    data object UnknownSources : GuideRequest

    /** Google Play services → Storage → Manage space → Clear all data (the last taps are the user's). */
    data object PlayServicesClearData : GuideRequest
}

/**
 * The walk-through the accessibility service should run right now. The app sets it immediately before
 * it starts a Settings screen and the service clears it when Tic says goodbye; the service shows
 * nothing while it is null, which keeps it inert everywhere else.
 */
object GuideSession {
    /** One start of [request]; [serial] makes a second start of the same request a new value. */
    data class Started(val request: GuideRequest, val serial: Long)

    private val _active = MutableStateFlow<Started?>(null)
    val active: StateFlow<Started?> = _active.asStateFlow()
    private var serial = 0L

    fun start(request: GuideRequest) {
        _active.value = Started(request, ++serial)
    }

    fun finish() {
        _active.value = null
    }

    /** Set while the user is in the accessibility settings to turn Tic on: the service greets them once and clears it. */
    @Volatile var greetOnConnect = false

    /** Uptime of the last greeting, so the app holds the next screen until it has been read. */
    @Volatile var greetedAt = 0L

    const val GREETING_MS = 3_000L

    /** Uptime until which the app stays behind Settings after pairing, so Tic's "got the code" can be read. */
    @Volatile var holdReturnUntil = 0L

    const val CODE_READ_MS = 3_500L
}
