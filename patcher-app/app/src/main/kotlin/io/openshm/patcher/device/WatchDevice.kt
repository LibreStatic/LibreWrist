package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbInstaller
import io.openshm.patcher.adb.ApkSource
import io.openshm.patcher.adb.BridgeStart
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.adb.GrantApplier
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.GrantTarget
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.PmParsers
import io.openshm.patcher.adb.RemotePackages
import java.io.File

/** What the watch screens need from the watch; the real one runs over ADB, tests use fakes. Failures are [AdbException]s. */
interface WatchDevice {
    suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult
    suspend fun applyGrants(pkg: String, grants: List<AdbGrant>): GrantReport
    suspend fun isInstalled(pkg: String): Boolean

    /**
     * Sends the bridge [pkg] its START broadcast (clears the stopped state, starts its service).
     * [prefix] is the package prefix of the build. Failures are [AdbException]s or [IOException]s.
     */
    suspend fun startBridge(pkg: String, prefix: String)

    /** `pm disable-user`; never uninstalls. */
    suspend fun disable(pkg: String)

    /** Whether [pkg] is installed on the watch, and if so whether it is a system package. */
    suspend fun packageKind(pkg: String): PackageKind

    /**
     * `pm uninstall --user 0` (data is removed, by design). Only called after the user confirmed it
     * for a package of the current build; the install screens never remove anything otherwise.
     */
    suspend fun uninstall(pkg: String): UninstallResult
}

class AdbWatchDevice(private val link: WatchLink) : WatchDevice {
    override suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult {
        val source = ApkSource("base.apk", apk.length()) { apk.inputStream() }
        return AdbInstaller(link.client()).install(listOf(source)) { written, total ->
            onProgress(if (total > 0) written.toFloat() / total else 0f)
        }
    }

    override suspend fun applyGrants(pkg: String, grants: List<AdbGrant>): GrantReport =
        GrantApplier(link.client(), GrantTarget.WATCH).apply(pkg, grants)

    override suspend fun isInstalled(pkg: String): Boolean = RemotePackages(link.client()).isInstalled(pkg)

    override suspend fun startBridge(pkg: String, prefix: String) {
        val command = BridgeStart.watchCommand(pkg, prefix)
        link.client().shell(command).requireSuccess(command)
    }

    override suspend fun disable(pkg: String) {
        RemotePackages(link.client()).disableUser(pkg)
    }

    override suspend fun packageKind(pkg: String): PackageKind {
        val packages = RemotePackages(link.client())
        return when {
            !packages.isInstalled(pkg) -> PackageKind.Missing
            packages.isSystem(pkg) -> PackageKind.System
            else -> PackageKind.User
        }
    }

    override suspend fun uninstall(pkg: String): UninstallResult {
        val result = RemotePackages(link.client()).uninstall(pkg)
        return if (PmParsers.isSuccess(result)) {
            UninstallResult.Success
        } else {
            UninstallResult.Failure((result.stdout + "\n" + result.stderr).trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "exit code ${result.exitCode}")
        }
    }
}
