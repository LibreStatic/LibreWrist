package io.openshm.patcher.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The choice made on the Profile screen: which watch the user has decides which inputs are needed. */
enum class WatchProfile(val inputIds: List<String>) {
    /** Wear OS 5 or newer: Health Monitor plus the phone-alarm fork (AlarmWatch needs Android 14). */
    WearOs5(listOf("shm-phone", "shm-watch", "alarm-watch")),

    /** Wear OS 3 or 4: Health Monitor only. */
    WearOs3Or4(listOf("shm-phone", "shm-watch")),
    ;

    val outputIds: List<String>
        get() = BundledCatalogue.outputs
            .filter { out -> out.inputId == null || out.inputId in inputIds }
            .map { it.id }
}

data class SessionState(
    val watchProfile: WatchProfile = WatchProfile.WearOs5,
    val packagePrefix: String = PackagePrefix.generate(),
    /** Once anything is installed the prefix is locked: an upgrade needs the same prefix and key. */
    val prefixLocked: Boolean = false,
)

/**
 * In-memory selections that later screens read (profile, prefix). Shell-stage holder: it is
 * replaced by the persisted setup store when the engine and ADB layers are wired in.
 */
object SetupSession {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun update(transform: (SessionState) -> SessionState) = _state.update(transform)
}
