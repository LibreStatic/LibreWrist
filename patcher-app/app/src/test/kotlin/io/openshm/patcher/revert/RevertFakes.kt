package io.openshm.patcher.revert

import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.PhoneInstaller
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.WatchDevice
import io.openshm.patcher.ui.screens.revert.RevertRunner
import java.io.File
import java.io.IOException
import io.openshm.patcher.adb.Grant as AdbGrant

internal const val PREFIX = "io.openshm.u123456"
internal const val OWN = "io.openshm.patcher"

internal fun outputPkg(id: String, prefix: String = PREFIX) = AppCatalogue.current.output(id).packageName(prefix)

/** The Samsung Health+ output, when the catalogue carries it (it came later than the others). */
internal val SHEALTH: String? get() = AppCatalogue.current.outputs.firstOrNull { it.id == "shealth-phone" }?.packageName(PREFIX)

/** How many phone apps [fullInstall] leaves. */
internal val PHONE_APPS: Int get() = if (SHEALTH == null) 2 else 3

internal val STOCK_HM: String get() = AppCatalogue.current.stockAppsToDisable.first { it.device == Device.Watch }.packageName

internal class FakePhone(
    val kinds: MutableMap<String, PackageKind> = mutableMapOf(),
    val listeners: List<String> = emptyList(),
) : PhoneInstaller {
    val calls = mutableListOf<String>()
    val results = mutableMapOf<String, UninstallResult>()
    val throwing = mutableMapOf<String, RuntimeException>()

    override fun canInstallPackages() = true
    override fun isUpToDate(apk: File, packageName: String) = false
    override fun startBridge(packageName: String, receiver: String, action: String) {}
    override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult = error("never installs")
    override fun packageKind(packageName: String) = kinds[packageName] ?: PackageKind.Missing
    override fun notificationListenerPackages() = listeners

    override suspend fun uninstall(packageName: String): UninstallResult {
        calls += "uninstall:$packageName"
        throwing[packageName]?.let { throw it }
        val result = results[packageName] ?: UninstallResult.Success
        if (result == UninstallResult.Success) kinds.remove(packageName)
        return result
    }
}

internal class FakeWatch(
    val kinds: MutableMap<String, PackageKind> = mutableMapOf(),
    val disabled: MutableSet<String> = mutableSetOf(),
    val settings: MutableMap<String, String> = mutableMapOf(),
) : WatchDevice {
    val calls = mutableListOf<String>()

    /** Every call throws this while set (the watch is off or not paired). */
    var down: IOException? = null

    /** Becomes [down] once this many uninstalls ran. */
    var dropAfterUninstalls: Int? = null
    val failing = mutableMapOf<String, UninstallResult>()
    val throwing = mutableMapOf<String, RuntimeException>()
    private var uninstalls = 0

    private fun check() {
        down?.let { throw it }
    }

    override suspend fun listPackages(): List<String> {
        check()
        return kinds.keys.toList()
    }

    override suspend fun packageKind(pkg: String): PackageKind {
        check()
        return kinds[pkg] ?: PackageKind.Missing
    }

    override suspend fun uninstall(pkg: String): UninstallResult {
        check()
        calls += "uninstall:$pkg"
        throwing[pkg]?.let { throw it }
        val result = failing[pkg] ?: UninstallResult.Success
        if (result == UninstallResult.Success) kinds.remove(pkg)
        uninstalls++
        if (uninstalls == dropAfterUninstalls) down = IOException("connection lost")
        return result
    }

    override suspend fun isDisabled(pkg: String): Boolean {
        check()
        return pkg in disabled
    }

    override suspend fun enable(pkg: String) {
        check()
        calls += "enable:$pkg"
        disabled.remove(pkg)
    }

    override suspend fun secureSetting(key: String): String? {
        check()
        return settings[key]
    }

    override suspend fun putSecureSetting(key: String, value: String) {
        check()
        calls += "put:$key=$value"
        settings[key] = value
    }

    override suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult = error("never installs")
    override suspend fun applyGrants(pkg: String, grants: List<AdbGrant>) = GrantReport(emptyList())
    override suspend fun isInstalled(pkg: String) = kinds[pkg] != null && kinds[pkg] != PackageKind.Missing
    override suspend fun startBridge(pkg: String, prefix: String) {}
    override suspend fun disable(pkg: String) = error("never disables here")
}

internal fun runner(
    phone: FakePhone,
    watch: FakeWatch,
    stored: String? = PREFIX,
) = RevertRunner(
    phone = phone,
    watch = watch,
    outputs = AppCatalogue.current.outputs,
    stockApps = AppCatalogue.current.stockAppsToDisable,
    storedPrefix = stored,
    ownPackage = OWN,
)

/** A phone and a watch as a full install under [PREFIX] leaves them (stock Health Monitor turned off, always-on display off). */
internal fun fullInstall(): Pair<FakePhone, FakeWatch> {
    val phone = FakePhone(
        kinds = listOfNotNull(outputPkg("bridge-phone"), SHEALTH, outputPkg("shm-phone"))
            .associateWithTo(mutableMapOf()) { PackageKind.User },
    )
    val watch = FakeWatch(
        kinds = mutableMapOf(
            outputPkg("bridge-watch") to PackageKind.User,
            outputPkg("alarm-fork") to PackageKind.User,
            outputPkg("shm-watch") to PackageKind.User,
            STOCK_HM to PackageKind.System,
        ),
        disabled = mutableSetOf(STOCK_HM),
        settings = mutableMapOf("doze_always_on" to "0"),
    )
    return phone to watch
}
