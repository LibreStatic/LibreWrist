package io.openshm.patcher

import android.app.Application
import android.util.Log
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.build.BuildService
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.SelfAdbService

/** Loads the verified catalogue and the persisted setup before any screen runs. */
class PatcherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) useFixedWatch()
        AppCatalogue.ensure(this)
        SetupSession.init(this)
        Automation.init(this)
        BuildService.clearStaleProgress(this)
        SelfAdbService.clearStale(this)
    }

    /**
     * Debug builds only: `adb shell setprop debug.openshm.watch_adb <host>:<port>` adds that address
     * to every ADB discovery (mDNS keeps running, so the phone still finds itself), so an emulated
     * watch (no Wi-Fi, so no Wireless debugging) can be reached through `adb forward` on the host. The connection uses the classic
     * RSA authorization, and the Wear OS emulator only accepts a key it already trusts (see
     * docs/patcher-app-handoff.md, "Debug-only fixed watch"). Read once at process start.
     */
    private fun useFixedWatch() {
        val value = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, WATCH_ADB_PROP) as String
        }.getOrNull().orEmpty()
        val host = value.substringBeforeLast(':', "")
        val port = value.substringAfterLast(':', "").toIntOrNull() ?: return
        if (host.isEmpty() || port !in 1..65535) return
        Log.w("PatcherApplication", "debug: watch ADB fixed at $host:$port, added to mDNS discovery")
        AdbDiscovery.fixedServices = listOf(AdbService("adb-debugwatch-0", host, port, AdbServiceType.CONNECT))
    }

    private companion object {
        const val WATCH_ADB_PROP = "debug.openshm.watch_adb"
    }
}
