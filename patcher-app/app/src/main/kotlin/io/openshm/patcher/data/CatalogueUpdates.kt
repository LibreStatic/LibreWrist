package io.openshm.patcher.data

import android.content.Context
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.build.BuildController
import io.openshm.patcher.build.BuildRunPhase
import java.io.File

/** [UpdateState] in the "catalogue" preferences. */
internal class PrefsUpdateState(context: Context) : UpdateState {
    private val prefs = context.applicationContext.getSharedPreferences("catalogue", Context.MODE_PRIVATE)

    override var highestSerial: Long
        get() = prefs.getLong("highestSerial", 0L)
        set(value) = prefs.edit().putLong("highestSerial", value).apply()

    var lastCheckMillis: Long
        get() = prefs.getLong("lastCheckMillis", 0L)
        set(value) = prefs.edit().putLong("lastCheckMillis", value).apply()
}

/**
 * Android glue of the catalogue update channel. A catalogue accepted while the app runs is only
 * marked pending: it replaces the one in [AppCatalogue] when Home asks ([adoptPendingIfIdle]) and no
 * build runs, otherwise at the next process start. Screens never see the catalogue change under them.
 */
object CatalogueUpdates {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile private var pending = false

    /** Whether a check is worth making now: an update URL is configured and the last one is a day old. */
    fun isDue(lastCheckMillis: Long, nowMillis: Long): Boolean =
        BuildConfig.CATALOGUE_URL.isNotEmpty() && (nowMillis - lastCheckMillis >= DAY_MS || lastCheckMillis > nowMillis)

    /** Runs a check (blocking, call off the main thread). [force] ignores the 24 h cadence. */
    fun check(context: Context, force: Boolean, nowMillis: Long = System.currentTimeMillis()): UpdateResult? {
        val app = context.applicationContext
        val state = PrefsUpdateState(app)
        if (!force && !isDue(state.lastCheckMillis, nowMillis)) return null
        if (BuildConfig.CATALOGUE_URL.isEmpty()) return UpdateResult.Disabled
        val pem = app.assets.open("catalogue/catalogue.pub").use { String(it.readBytes(), Charsets.UTF_8) }
        val result = CatalogueUpdater(
            BuildConfig.CATALOGUE_URL, pem, BuildConfig.VERSION_CODE, AppCatalogue.bundledSerial,
            StoredCatalogue(File(app.filesDir, "catalogue")), state, HttpsCatalogueFetcher(),
        ).check()
        // Stamped on every attempt, a failed one included, so an offline phone is not retried on every resume.
        state.lastCheckMillis = nowMillis
        if (result is UpdateResult.Updated) pending = true
        return result
    }

    /** Installs the accepted catalogue when no build is running; true when the catalogue changed. */
    fun adoptPendingIfIdle(context: Context): Boolean {
        if (!pending || BuildController.state.value.phase == BuildRunPhase.Running) return false
        pending = false
        AppCatalogue.adoptStored(context)
        return true
    }
}
