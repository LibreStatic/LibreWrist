package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.AdbInstaller
import io.openshm.patcher.adb.ApkSource
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.PmParsers
import io.openshm.patcher.adb.RemotePackages
import java.io.File

/**
 * Installs and removes packages on this phone through its own Wireless debugging session, so
 * unattended mode shows no system confirmation and needs no "Install unknown apps" permission.
 * Queries and the bridge start stay with [platform] (they need no shell and work without ADB).
 */
class AdbPhoneInstaller(
    private val client: AdbClient,
    private val platform: PhoneInstaller,
) : PhoneInstaller {
    /** The shell installs on its own: there is nothing for the user to allow. */
    override fun canInstallPackages(): Boolean = true

    override fun isUpToDate(apk: File, packageName: String): Boolean = platform.isUpToDate(apk, packageName)

    override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult {
        val source = ApkSource("base.apk", apk.length()) { apk.inputStream() }
        return AdbInstaller(client).install(listOf(source)) { written, total ->
            onProgress(if (total > 0) written.toFloat() / total else 0f)
        }
    }

    override fun packageKind(packageName: String): PackageKind = platform.packageKind(packageName)

    override fun startBridge(packageName: String, receiver: String, action: String) =
        platform.startBridge(packageName, receiver, action)

    override suspend fun uninstall(packageName: String): UninstallResult {
        val result = RemotePackages(client).uninstall(packageName)
        return if (PmParsers.isSuccess(result)) {
            UninstallResult.Success
        } else {
            UninstallResult.Failure(
                (result.stdout + "\n" + result.stderr).trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    ?: "exit code ${result.exitCode}",
            )
        }
    }

    override fun notificationListenerPackages(): List<String> = platform.notificationListenerPackages()
}
