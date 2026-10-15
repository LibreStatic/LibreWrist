package io.openshm.patcher.adb

import android.content.Context

/** Remembers the host and connect port of the last working self-ADB connection (not a secret). */
interface SelfAdbEndpointStore {
    fun load(): Pair<String, Int>?

    fun save(host: String, port: Int)
}

/** [SelfAdbEndpointStore] backed by a private SharedPreferences file. */
class PrefsSelfAdbEndpointStore(context: Context) : SelfAdbEndpointStore {
    private val prefs = context.applicationContext.getSharedPreferences("self_adb_endpoint", Context.MODE_PRIVATE)

    override fun load(): Pair<String, Int>? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val port = prefs.getInt(KEY_PORT, 0)
        return if (host.isNotEmpty() && port in 1..65535) host to port else null
    }

    override fun save(host: String, port: Int) {
        prefs.edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
    }
}
