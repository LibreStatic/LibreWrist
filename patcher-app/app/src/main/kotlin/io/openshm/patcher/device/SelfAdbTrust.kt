package io.openshm.patcher.device

import android.content.Context

/**
 * Remembers that this phone's own Wireless debugging trusted this app's key at least once. It only
 * gates features that listen on the network (see [AdbPresence]); it never grants anything.
 */
object SelfAdbTrust {
    private const val PREFS = "self_adb"
    private const val KEY_TRUSTED = "trusted"

    /** Called when a self-ADB session reached Connected. */
    fun record(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_TRUSTED, false)) prefs.edit().putBoolean(KEY_TRUSTED, true).apply()
    }

    fun isTrusted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TRUSTED, false)
}
