package io.openshm.patcher.data

import android.content.Context

/** [AppUpdateSettings] in the "app-update" SharedPreferences: the switch, timestamps and the last found build. */
class AppUpdatePrefs(context: Context) : AppUpdateSettings {
    private val prefs = context.applicationContext.getSharedPreferences("app-update", Context.MODE_PRIVATE)

    override var autoCheck: Boolean
        get() = prefs.getBoolean(AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(AUTO_CHECK, value).apply()

    override var lastCheckMillis: Long
        get() = prefs.getLong(LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(LAST_CHECK, value).apply()

    override var lastNotifiedBuild: Long
        get() = prefs.getLong(LAST_NOTIFIED, 0L)
        set(value) = prefs.edit().putLong(LAST_NOTIFIED, value).apply()

    override var cachedUpdate: AppUpdate?
        get() {
            val build = prefs.getLong(CACHE_BUILD, 0L)
            if (build <= 0L) return null
            return AppUpdate(
                build,
                prefs.getString(CACHE_VERSION, null) ?: return null,
                prefs.getString(CACHE_URL, null) ?: return null,
                prefs.getString(CACHE_NAME, null) ?: return null,
                prefs.getString(CACHE_SHA256, null) ?: return null,
                prefs.getString(CACHE_NOTES, "").orEmpty(),
                prefs.getLong(CACHE_SIZE, 0L),
            )
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                listOf(CACHE_BUILD, CACHE_VERSION, CACHE_URL, CACHE_NAME, CACHE_SHA256, CACHE_NOTES, CACHE_SIZE).forEach(edit::remove)
            } else {
                edit.putLong(CACHE_BUILD, value.build)
                    .putString(CACHE_VERSION, value.versionName)
                    .putString(CACHE_URL, value.apkUrl)
                    .putString(CACHE_NAME, value.apkName)
                    .putString(CACHE_SHA256, value.sha256)
                    .putString(CACHE_NOTES, value.notes)
                    .putLong(CACHE_SIZE, value.sizeBytes)
            }
            edit.apply()
        }

    override var skippedBuild: Long
        get() = prefs.getLong(SKIPPED, 0L)
        set(value) = prefs.edit().putLong(SKIPPED, value).apply()

    private companion object {
        const val SKIPPED = "skippedBuild"
        const val CACHE_BUILD = "cache.build"
        const val CACHE_VERSION = "cache.versionName"
        const val CACHE_URL = "cache.apkUrl"
        const val CACHE_NAME = "cache.apkName"
        const val CACHE_SHA256 = "cache.sha256"
        const val CACHE_NOTES = "cache.notes"
        const val CACHE_SIZE = "cache.sizeBytes"
        const val LAST_NOTIFIED = "lastNotifiedBuild"
        const val AUTO_CHECK = "autoCheck"
        const val LAST_CHECK = "lastCheckMillis"
    }
}
