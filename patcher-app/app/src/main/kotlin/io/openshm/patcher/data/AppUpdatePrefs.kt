package io.openshm.patcher.data

import android.content.Context

/** [AppUpdateSettings] in the "app-update" SharedPreferences. Holds a switch and a timestamp only. */
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

    private companion object {
        const val LAST_NOTIFIED = "lastNotifiedBuild"
        const val AUTO_CHECK = "autoCheck"
        const val LAST_CHECK = "lastCheckMillis"
    }
}
