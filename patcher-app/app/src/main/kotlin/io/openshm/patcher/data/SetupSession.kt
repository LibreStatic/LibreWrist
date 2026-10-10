package io.openshm.patcher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The choice made on the Profile screen: which watch the user has decides which inputs are needed. */
enum class WatchProfile(val inputIds: List<String>) {
    /** Wear OS 5 or newer: Health Monitor plus the phone-alarm fork (AlarmWatch needs Android 14). */
    WearOs5(listOf("shm-phone", "shm-watch", "alarm-watch")),

    /** Wear OS 3 or 4: Health Monitor only. */
    WearOs3Or4(listOf("shm-phone", "shm-watch")),
    ;

    val outputIds: List<String>
        get() = AppCatalogue.current.outputs
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
 * The selections that later screens read (profile, prefix). Persisted in SharedPreferences once
 * [init] has run (the app does it in `PatcherApplication`); purely in-memory before that, which is
 * what unit tests see.
 */
object SetupSession {
    private const val PREFS = "setup"
    private const val KEY_PROFILE = "watchProfile"
    private const val KEY_PREFIX = "packagePrefix"
    private const val KEY_LOCKED = "prefixLocked"

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null

    /** Loads the stored setup; a first run stores the freshly generated prefix so it stays stable. */
    @Synchronized
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val locked = p.getBoolean(KEY_LOCKED, false)
        // A locked prefix is kept even if the validation rules changed: installed apps depend on it.
        val stored = p.getString(KEY_PREFIX, null)?.takeIf { locked || PackagePrefix.validate(it) == null }
        val loaded = SessionState(
            watchProfile = WatchProfile.entries.firstOrNull { it.name == p.getString(KEY_PROFILE, null) }
                ?: SessionState().watchProfile,
            packagePrefix = stored ?: PackagePrefix.generate(),
            prefixLocked = locked && stored != null,
        )
        prefs = p
        _state.value = loaded
        persist(p, loaded)
    }

    @Synchronized
    fun update(transform: (SessionState) -> SessionState) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.let { persist(it, next) }
    }

    private fun persist(p: SharedPreferences, s: SessionState) {
        p.edit()
            .putString(KEY_PROFILE, s.watchProfile.name)
            .putString(KEY_PREFIX, s.packagePrefix)
            .putBoolean(KEY_LOCKED, s.prefixLocked)
            .apply()
    }
}
