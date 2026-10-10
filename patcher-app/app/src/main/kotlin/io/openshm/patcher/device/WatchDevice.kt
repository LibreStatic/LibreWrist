package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbInstaller
import io.openshm.patcher.adb.ApkSource
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.adb.GrantApplier
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.GrantTarget
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.RemotePackages
import java.io.File

/** What the watch screens need from the watch; the real one runs over ADB, tests use fakes. Failures are [AdbException]s. */
interface WatchDevice {
    suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult
    suspend fun applyGrants(pkg: String, grants: List<AdbGrant>): GrantReport
    suspend fun isInstalled(pkg: String): Boolean

    /** `pm disable-user`; never uninstalls. */
    suspend fun disable(pkg: String)
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

    override suspend fun disable(pkg: String) {
        RemotePackages(link.client()).disableUser(pkg)
    }
}
