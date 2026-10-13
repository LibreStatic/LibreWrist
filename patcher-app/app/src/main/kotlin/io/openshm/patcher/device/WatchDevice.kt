package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbInstaller
import io.openshm.patcher.adb.ApkSource
import io.openshm.patcher.adb.BridgeStart
import io.openshm.patcher.adb.CompanionAssociation
import io.openshm.patcher.adb.CompanionCommands
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.adb.GrantApplier
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.GrantTarget
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.PmParsers
import io.openshm.patcher.adb.RemotePackages
import io.openshm.patcher.build.BuildRunner
import io.openshm.patcher.ui.screens.selftest.SelfTestParsers
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the watch screens need from the watch; the real one runs over ADB, tests use fakes. Failures are [AdbException]s. */
interface WatchDevice {
    suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult

    /**
     * True when [pkg] is installed on the watch from exactly [apk] (same bytes), so sending it again
     * over the watch's slow Wi-Fi would change nothing. False when unsure.
     */
    suspend fun isUpToDate(pkg: String, apk: File): Boolean = false
    suspend fun applyGrants(pkg: String, grants: List<AdbGrant>): GrantReport
    suspend fun isInstalled(pkg: String): Boolean

    /**
     * Sends the bridge [pkg] its START broadcast (clears the stopped state, starts its service).
     * [prefix] is the package prefix of the build. Failures are [AdbException]s or [IOException]s.
     */
    suspend fun startBridge(pkg: String, prefix: String)

    /** `pm disable-user`; never uninstalls. */
    suspend fun disable(pkg: String)

    /** True when [pkg] is installed but turned off (`pm list packages -d`). */
    suspend fun isDisabled(pkg: String): Boolean = false

    /** Whether [pkg] is installed on the watch, and if so whether it is a system package. */
    suspend fun packageKind(pkg: String): PackageKind

    /**
     * `pm uninstall --user 0` (data is removed, by design). Only called after the user confirmed it
     * for a package of the current build; the install screens never remove anything otherwise.
     */
    suspend fun uninstall(pkg: String): UninstallResult

    /** `pm enable`: turns [pkg] back on after [disable]. The revert is the only caller. */
    suspend fun enable(pkg: String) {
        throw UnsupportedOperationException("enable is not supported")
    }

    /** The value of the secure setting [key] (`settings get secure`), or null when it is unset. */
    suspend fun secureSetting(key: String): String? = null

    /** `settings put secure [key] [value]`. The revert is the only caller. */
    suspend fun putSecureSetting(key: String, value: String) {
        throw UnsupportedOperationException("putSecureSetting is not supported")
    }

    /** Every package installed on the watch (`pm list packages`). */
    suspend fun listPackages(): List<String> = emptyList()

    /** The phone's Bluetooth MAC as the bridge [pkg] recorded it (its link provider), or null when unknown or the bridge is older. */
    suspend fun phoneMac(pkg: String): String? = null

    /** The watch's Companion Device Manager associations (user 0). */
    suspend fun companionAssociations(): List<CompanionAssociation> = emptyList()

    /** Associates [pkg] with the device at [mac] in the watch's Companion Device Manager. */
    suspend fun associateCompanion(pkg: String, mac: String) {
        throw UnsupportedOperationException("associateCompanion is not supported")
    }

    /** Removes the association of [pkg] with [mac]. */
    suspend fun disassociateCompanion(pkg: String, mac: String) {
        throw UnsupportedOperationException("disassociateCompanion is not supported")
    }
}

class AdbWatchDevice(private val link: WatchLink) : WatchDevice {
    override suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult {
        val source = ApkSource("base.apk", apk.length()) { apk.inputStream() }
        return AdbInstaller(link.client()).install(listOf(source)) { written, total ->
            onProgress(if (total > 0) written.toFloat() / total else 0f)
        }
    }

    override suspend fun isUpToDate(pkg: String, apk: File): Boolean {
        val adb = link.client()
        val paths = adb.shell("pm path $pkg").takeIf { it.isSuccess }?.let { PmParsers.parsePaths(it.stdout) }.orEmpty()
        // A split install was not sent by us: send ours.
        val path = paths.singleOrNull()?.takeIf { SelfTestParsers.isSafePath(it) } ?: return false
        val remote = adb.shell("sha256sum $path", HASH_TIMEOUT_MS).takeIf { it.isSuccess }?.let { SelfTestParsers.sha256sum(it.stdout) }
            ?: return false
        return remote == withContext(Dispatchers.IO) { BuildRunner.sha256(apk) }
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

    override suspend fun isDisabled(pkg: String): Boolean {
        val out = link.client().shell("pm list packages -d")
        return out.isSuccess && pkg in PmParsers.parsePackageList(out.stdout)
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

    override suspend fun enable(pkg: String) {
        RemotePackages(link.client()).enable(pkg)
    }

    override suspend fun secureSetting(key: String): String? {
        val command = "settings get secure ${requireSettingKey(key)}"
        val out = link.client().shell(command).requireSuccess(command).stdout.trim()
        return out.takeIf { it.isNotEmpty() && it != "null" }
    }

    override suspend fun putSecureSetting(key: String, value: String) {
        require(value.matches(SETTING_VALUE)) { "Not a valid setting value" }
        val command = "settings put secure ${requireSettingKey(key)} $value"
        link.client().shell(command).requireSuccess(command)
    }

    override suspend fun listPackages(): List<String> = RemotePackages(link.client()).listPackages()

    override suspend fun phoneMac(pkg: String): String? {
        val out = link.client().shell(CompanionCommands.linkInfoCommand(pkg))
        return if (out.isSuccess) CompanionCommands.parsePhoneMac(out.stdout) else null
    }

    override suspend fun companionAssociations(): List<CompanionAssociation> =
        CompanionCommands.parseAssociations(link.client().shell(CompanionCommands.LIST_COMMAND).requireSuccess(CompanionCommands.LIST_COMMAND).stdout)

    override suspend fun associateCompanion(pkg: String, mac: String) {
        val command = CompanionCommands.associateCommand(pkg, mac)
        link.client().shell(command).requireSuccess(command)
    }

    override suspend fun disassociateCompanion(pkg: String, mac: String) {
        val command = CompanionCommands.disassociateCommand(pkg, mac)
        link.client().shell(command).requireSuccess(command)
    }

    private fun requireSettingKey(key: String): String {
        require(key.matches(SETTING_KEY)) { "Not a valid setting key" }
        return key
    }

    private companion object {
        val SETTING_KEY = Regex("[A-Za-z0-9_]+")
        val SETTING_VALUE = Regex("[A-Za-z0-9_.-]+")
        const val HASH_TIMEOUT_MS = 60_000L
    }
}
