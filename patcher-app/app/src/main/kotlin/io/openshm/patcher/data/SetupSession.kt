package io.openshm.patcher.data

import android.content.Context
import android.content.SharedPreferences
import io.openshm.patcher.engine.Catalogue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The choice made on the Profile screen. [sdk] is the highest Android SDK the watch family runs (Wear OS
 * 5 is Android 14, Wear OS 3 and 4 are Android 12 and 13); an official watch app whose `minSdk` is above
 * it cannot be installed there and is left out of the setup.
 */
enum class WatchProfile(val sdk: Int) {
    WearOs5(34),
    WearOs3Or4(33),
}

data class SessionState(
    val watchProfile: WatchProfile = WatchProfile.WearOs5,
    val packagePrefix: String = PackagePrefix.generate(),
    /** Once anything is installed the prefix is locked: an upgrade needs the same prefix and key. */
    val prefixLocked: Boolean = false,
    /** Ids of the optional patches the user switched off on the Customize screen; empty means every
     *  optional patch is applied. Each `offByDefault` pattern is added once, the first time the app
     *  sees it (see [seedOffByDefault]); after that the user's choice stands. */
    val disabledPatches: Set<String> = emptySet(),
    /** Ids of the optional catalogue apps the user switched off on the Acquire screen; empty means all are included. */
    val skippedApps: Set<String> = emptySet(),
    /** Ids of the outputs the user restored (removed) from Home; they stay out of Home and the self-test until installed again. */
    val restoredOutputs: Set<String> = emptySet(),
) {
    /** The outputs of this setup (see [LoadedCatalogue.selectedOutputs]), in the catalogue `installOrder` order. */
    val outputIds: List<String>
        get() = AppCatalogue.current.selectedOutputs(watchProfile.sdk, skippedApps).map { it.id }

    /** The official apps this setup needs: the inputs of [outputIds], in catalogue order. */
    val inputIds: List<String>
        get() {
            val needed = AppCatalogue.current.selectedOutputs(watchProfile.sdk, skippedApps).mapNotNull { it.inputId }.toSet()
            return AppCatalogue.current.inputs.map { it.id }.filter { it in needed }
        }

    /** [outputIds] without the ones the user restored from Home: they are left out until installed again. */
    val activeOutputIds: List<String> get() = outputIds.filter { it !in restoredOutputs }
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
    private const val KEY_LEGACY_SAMSUNG_HEALTH = "includeSamsungHealth" // dropped flag, removed on the next save
    private const val KEY_SKIPPED_APPS = "skippedApps"
    private const val KEY_RESTORED = "restoredOutputs"

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
            skippedApps = p.getStringSet(KEY_SKIPPED_APPS, null)?.toSet().orEmpty(),
            restoredOutputs = p.getStringSet(KEY_RESTORED, null)?.toSet().orEmpty(),
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
            .putStringSet(KEY_SKIPPED_APPS, s.skippedApps.toSet())
            .remove(KEY_LEGACY_SAMSUNG_HEALTH)
            .putStringSet(KEY_RESTORED, s.restoredOutputs.toSet())
            .apply()
    }
}
