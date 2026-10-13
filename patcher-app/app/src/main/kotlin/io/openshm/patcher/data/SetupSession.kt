package io.openshm.patcher.data

import android.content.Context
import android.content.SharedPreferences
import io.openshm.patcher.engine.Catalogue
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
        get() = outputIdsFor(inputIds)
}

/** Input of the opt-in Samsung Health build (Profile screen), on top of the watch's inputs. */
const val SAMSUNG_HEALTH_INPUT = "shealth-phone"

/** The outputs built from [inputIds] plus our own apps, in the catalogue `installOrder` order. */
fun outputIdsFor(inputIds: Collection<String>): List<String> =
    AppCatalogue.current.outputs
        .filter { out -> out.inputId == null || out.inputId in inputIds }
        .map { it.id }

data class SessionState(
    val watchProfile: WatchProfile = WatchProfile.WearOs5,
    val packagePrefix: String = PackagePrefix.generate(),
    /** Once anything is installed the prefix is locked: an upgrade needs the same prefix and key. */
    val prefixLocked: Boolean = false,
    /** Ids of the optional patches the user switched off on the Customize screen; empty means every
     *  optional patch is applied. Each `offByDefault` pattern is added once, the first time the app
     *  sees it (see [seedOffByDefault]); after that the user's choice stands. */
    val disabledPatches: Set<String> = emptySet(),
    /** Also patch Samsung Health (on by default, opt-out on the Acquire screen): it replaces the installed copy. */
    val samsungHealth: Boolean = true,
) {
    /** The official apps this setup needs: the watch's, plus Samsung Health when chosen. */
    val inputIds: List<String>
        get() = watchProfile.inputIds + listOfNotNull(SAMSUNG_HEALTH_INPUT.takeIf { samsungHealth })

    val outputIds: List<String> get() = outputIdsFor(inputIds)
}

/**
 * The disabled set after loading: [stored] (null on a first run) plus every `offByDefault` pattern of
 * [catalogue] that is not in [known], the optional patterns this app had already seen. A pattern is
 * therefore switched off by default exactly once; turning it on later sticks across catalogue updates.
 * Returns the disabled set and the new known set.
 */
internal fun seedOffByDefault(stored: Set<String>?, known: Set<String>?, catalogue: Catalogue): Pair<Set<String>, Set<String>> {
    val seen = known.orEmpty()
    val fresh = catalogue.patterns.values.filter { it.offByDefault && it.id !in seen }.map { it.id }
    val optional = catalogue.patterns.values.filter { it.optional }.map { it.id }
    return (stored.orEmpty() + fresh) to (seen + optional)
}

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
    private const val KEY_DISABLED = "disabledPatches"
    private const val KEY_KNOWN_OPTIONAL = "knownOptionalPatches"
    private const val KEY_SAMSUNG_HEALTH = "includeSamsungHealth"

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var knownOptional: Set<String> = emptySet()

    /** Loads the stored setup; a first run stores the freshly generated prefix so it stays stable. */
    @Synchronized
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val locked = p.getBoolean(KEY_LOCKED, false)
        // A locked prefix is kept even if the validation rules changed: installed apps depend on it.
        val stored = p.getString(KEY_PREFIX, null)?.takeIf { locked || PackagePrefix.validate(it) == null }
        val (disabled, known) = seedOffByDefault(
            p.getStringSet(KEY_DISABLED, null)?.toSet(),
            p.getStringSet(KEY_KNOWN_OPTIONAL, null)?.toSet(),
            AppCatalogue.current.engine,
        )
        knownOptional = known
        val loaded = SessionState(
            watchProfile = WatchProfile.entries.firstOrNull { it.name == p.getString(KEY_PROFILE, null) }
                ?: SessionState().watchProfile,
            packagePrefix = stored ?: PackagePrefix.generate(),
            prefixLocked = locked && stored != null,
            disabledPatches = disabled,
            samsungHealth = p.getBoolean(KEY_SAMSUNG_HEALTH, true),
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
            .putStringSet(KEY_DISABLED, s.disabledPatches.toSet())
            .putStringSet(KEY_KNOWN_OPTIONAL, knownOptional)
            .putBoolean(KEY_SAMSUNG_HEALTH, s.samsungHealth)
            .apply()
    }
}
