package io.openshm.patcher.data

import android.content.Context

/**
 * Remembers which runtime permissions this app has already asked for. Android reports "do not show
 * a rationale" both before the first ask and after a permanent denial; this tells the two apart, so
 * a permission is asked for automatically only once and a permanent denial leads to the app settings.
 */
class PermissionAsks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasAsked(permission: String): Boolean = prefs.getBoolean(permission, false)

    fun markAsked(permission: String) {
        prefs.edit().putBoolean(permission, true).apply()
    }

    private companion object {
        const val PREFS = "permission_asks"
    }
}
