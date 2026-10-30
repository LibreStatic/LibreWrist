package io.openshm.patcher

import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.PhoneInstaller
import io.openshm.patcher.device.PhoneProbe
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.install.PhoneInstallRunner
import io.openshm.patcher.ui.screens.install.adbInstallBlocked
import io.openshm.patcher.ui.screens.install.installFailureRes
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val PREFIX = "io.openshm.u123456"
private const val RESTRICTED = "INSTALL_FAILED_USER_RESTRICTED"

private class RestrictedNoProbe : PhoneProbe {
    override fun permissionGranted(pkg: String, permission: String) = false
    override fun appOpAllowed(pkg: String, op: String): Boolean? = null
    override fun listenerEnabled(pkg: String, component: String) = false
}

private class ScriptedPhone(var result: InstallResult) : PhoneInstaller {
    val calls = mutableListOf<String>()
    override fun canInstallPackages() = true
    override fun isUpToDate(apk: File, packageName: String) = false
    override fun packageKind(packageName: String) = PackageKind.System
    override suspend fun uninstall(packageName: String) = UninstallResult.Success
    override fun startBridge(packageName: String, receiver: String, action: String) {}
    override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult {
        calls += packageName
        return result
    }
}

class AdbInstallRestrictedTest {
    private lateinit var dir: File
    private val restricted = InstallResult.Failure(RESTRICTED, "Install canceled by user", InstallResult.Stage.COMMIT)

    @Before fun setUp() {
        dir = Files.createTempDirectory("adb-restricted").toFile()
        TestCatalogue.install()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun built() = BuiltBuild(
        "k", PREFIX, 1, 0,
        listOf("shm-phone").map { id -> File(dir, "$id.apk").apply { writeBytes(ByteArray(8) { 1 }) }.let { BuiltRecord(id, it.path, it.length(), "00") } },
    )

    private fun state() = MutableStateFlow(
        InstallUiState(listOf(InstallItem("shm-phone", "Health Monitor", "$PREFIX.shm", CheckStatus.Pending))),
    )

    private fun runner(installer: PhoneInstaller) = PhoneInstallRunner(installer, PhoneGrants(RestrictedNoProbe()), { built() }, { PREFIX }, {})

    @Test
    fun theRestrictedCodeHasItsOwnMessage() {
        assertEquals(R.string.install_error_adb_restricted, installFailureRes(RESTRICTED))
        assertEquals(R.string.install_error_signature, installFailureRes("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertEquals(R.string.install_error_failed, installFailureRes("INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(R.string.install_error_failed, installFailureRes(null))
    }

    @Test
    fun aRestrictedInstallSetsTheFlagAndTheMessage() = runBlocking {
        val s = state()
        runner(ScriptedPhone(restricted)).run(s)
        assertEquals(R.string.install_error_adb_restricted, s.value.errorRes)
        assertTrue(s.value.adbInstallRestricted)
        assertFalse(s.value.running)
    }

    @Test
    fun aGenericFailureLeavesTheFlagOff() = runBlocking {
        val s = state()
        runner(ScriptedPhone(InstallResult.Failure("INSTALL_FAILED_INVALID_APK", "bad", InstallResult.Stage.COMMIT))).run(s)
        assertEquals(R.string.install_error_failed, s.value.errorRes)
        assertFalse(s.value.adbInstallRestricted)
    }

    @Test
    fun aNewRunClearsTheFlag() = runBlocking {
        val s = state()
        val phone = ScriptedPhone(restricted)
        val r = runner(phone)
        r.run(s)
        assertTrue(s.value.adbInstallRestricted)
        phone.result = InstallResult.Success(1)
        r.run(s)
        assertFalse(s.value.adbInstallRestricted)
        assertNull(s.value.errorRes)
        assertEquals(CheckStatus.Passed, s.value.items.single().status)
    }

    @Test
    fun withoutTheOverrideTheRunUsesThePlatformInstaller() = runBlocking {
        val platform = ScriptedPhone(InstallResult.Success(1))
        val viaAdb = ScriptedPhone(restricted)
        val r = runner(platform)
        r.installerOverride = viaAdb
        val s = state()
        r.run(s)
        assertEquals(listOf("$PREFIX.shm"), viaAdb.calls)
        assertTrue(platform.calls.isEmpty())
        assertTrue(s.value.adbInstallRestricted)
        r.installerOverride = null
        r.run(s)
        assertEquals(listOf("$PREFIX.shm"), platform.calls)
        assertFalse(s.value.adbInstallRestricted)
    }

    @Test
    fun onlyAZeroFlagBlocks() {
        assertTrue(adbInstallBlocked("0\n"))
        assertTrue(adbInstallBlocked(" 0 "))
        assertFalse(adbInstallBlocked("1"))
        assertFalse(adbInstallBlocked(""))
        assertFalse(adbInstallBlocked("\n"))
        assertFalse(adbInstallBlocked("00"))
        assertFalse(adbInstallBlocked("getprop: not found"))
    }
}
