package io.openshm.patcher

import android.content.pm.PackageInstaller
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.data.Device
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.PhoneInstaller
import io.openshm.patcher.device.PhoneProbe
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.WatchDevice
import io.openshm.patcher.device.installResultFor
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.install.PhoneInstallRunner
import io.openshm.patcher.ui.screens.install.SignatureConflict
import io.openshm.patcher.ui.screens.install.WatchInstallRunner
import io.openshm.patcher.ui.screens.install.cancelReinstall
import io.openshm.patcher.ui.screens.install.isSignatureConflict
import io.openshm.patcher.ui.screens.install.requestReinstall
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
import io.openshm.patcher.adb.Grant as AdbGrant

private const val PREFIX = "io.openshm.u123456"
private const val INCOMPATIBLE = "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

private val signatureFailure = InstallResult.Failure(INCOMPATIBLE, "signatures do not match", InstallResult.Stage.COMMIT)

/** A device whose installed copy was signed by another key until it is uninstalled. */
private class ConflictingPhone(var kind: PackageKind = PackageKind.User) : PhoneInstaller {
    val calls = mutableListOf<String>()
    var uninstallResult: UninstallResult = UninstallResult.Success
    var installed = false

    override fun canInstallPackages() = true
    override fun isUpToDate(apk: File, packageName: String) = false
    override fun packageKind(packageName: String) = kind
    override fun startBridge(packageName: String, receiver: String, action: String) {}
    override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult {
        calls += "install:$packageName"
        return if (kind != PackageKind.Missing) signatureFailure else InstallResult.Success(1).also { installed = true }
    }

    override suspend fun uninstall(packageName: String): UninstallResult {
        calls += "uninstall:$packageName"
        if (uninstallResult == UninstallResult.Success) kind = PackageKind.Missing
        return uninstallResult
    }
}

private class ConflictingWatch(var kind: PackageKind = PackageKind.User, var failure: InstallResult.Failure = signatureFailure) : WatchDevice {
    val calls = mutableListOf<String>()
    var uninstallResult: UninstallResult = UninstallResult.Success
    var uninstallThrows: java.io.IOException? = null

    override suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult {
        calls += "install:${apk.nameWithoutExtension}"
        return if (kind != PackageKind.Missing) failure else InstallResult.Success(1)
    }

    override suspend fun applyGrants(pkg: String, grants: List<AdbGrant>) = GrantReport(emptyList())

    override suspend fun startBridge(pkg: String, receiver: String, action: String, extras: Map<String, Boolean>) {}
    override suspend fun isInstalled(pkg: String) = kind != PackageKind.Missing
    override suspend fun disable(pkg: String) = error("never disables here")
    override suspend fun packageKind(pkg: String) = kind
    override suspend fun uninstall(pkg: String): UninstallResult {
        calls += "uninstall:$pkg"
        uninstallThrows?.let { throw it }
        if (uninstallResult == UninstallResult.Success) kind = PackageKind.Missing
        return uninstallResult
    }
}

private class NoProbe : PhoneProbe {
    override fun permissionGranted(pkg: String, permission: String) = false
    override fun appOpAllowed(pkg: String, op: String): Boolean? = null
    override fun listenerEnabled(pkg: String, component: String) = false
}

class SignatureConflictDetectionTest {
    @Test
    fun theInstallerSessionStatusAndThePackageManagerCodeBothCount() {
        val conflictStatus = installResultFor(PackageInstaller.STATUS_FAILURE_CONFLICT, null, 0) as InstallResult.Failure
        assertTrue(isSignatureConflict(conflictStatus.code, conflictStatus.message))
        val legacy = installResultFor(PackageInstaller.STATUS_FAILURE, "Package signatures do not match", -7) as InstallResult.Failure
        assertTrue(isSignatureConflict(legacy.code, legacy.message))
    }

    @Test
    fun theWatchPmOutputCounts() {
        assertTrue(isSignatureConflict(null, "Error: INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package x signatures do not match"))
        assertTrue(isSignatureConflict(null, "Package x signatures do not match previously installed version"))
        assertTrue(isSignatureConflict(INCOMPATIBLE, ""))
    }

    @Test
    fun otherRefusalsDoNot() {
        assertFalse(isSignatureConflict("INSTALL_FAILED_VERSION_DOWNGRADE", "Downgrade detected"))
        assertFalse(isSignatureConflict("STATUS_FAILURE_STORAGE", ""))
        assertFalse(isSignatureConflict(null, null))
    }
}

class PhoneReinstallTest {
    private lateinit var dir: File
    private val installer = ConflictingPhone()

    @Before fun setUp() {
        dir = Files.createTempDirectory("phone-reinstall").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun built(vararg ids: String) = BuiltBuild(
        "k", PREFIX, 1, 0,
        ids.map { id -> File(dir, "$id.apk").apply { writeBytes(ByteArray(8) { 1 }) }.let { BuiltRecord(id, it.path, it.length(), "00") } },
    )

    private fun state() = MutableStateFlow(
        InstallUiState(listOf(InstallItem("shm-phone", "Health Monitor", "$PREFIX.shm", CheckStatus.Pending))),
    )

    private fun runner(build: BuiltBuild? = built("shm-phone")) =
        PhoneInstallRunner(installer, PhoneGrants(NoProbe()), { build }, { PREFIX }, {})

    @Test
    fun aConflictOnAUserAppIsOfferedAndNothingIsRemoved() = runBlocking {
        val s = state()
        runner().run(s)
        assertEquals(SignatureConflict("shm-phone", "$PREFIX.shm", Device.Phone), s.value.conflict)
        assertEquals(R.string.reinstall_error_phone, s.value.errorRes)
        assertFalse(s.value.confirmingReinstall)
        assertEquals(CheckStatus.Failed, s.value.items.single().status)
        assertEquals(listOf("install:$PREFIX.shm"), installer.calls)
    }

    @Test
    fun aSystemAppGetsThePlainErrorAndNoOffer() = runBlocking {
        installer.kind = PackageKind.System
        val s = state()
        runner().run(s)
        assertNull(s.value.conflict)
        assertEquals(R.string.install_error_signature, s.value.errorRes)
    }

    @Test
    fun aPackageThatIsNotVisibleGetsThePlainError() = runBlocking {
        installer.kind = PackageKind.Missing
        val failing = object : PhoneInstaller by installer {
            override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit) = signatureFailure
        }
        val s = state()
        PhoneInstallRunner(failing, PhoneGrants(NoProbe()), { built("shm-phone") }, { PREFIX }, {}).run(s)
        assertNull(s.value.conflict)
        assertEquals(R.string.install_error_signature, s.value.errorRes)
    }

    @Test
    fun cancellingDoesNothing() = runBlocking {
        val s = state()
        runner().run(s)
        requestReinstall(s)
        assertTrue(s.value.confirmingReinstall)
        cancelReinstall(s)
        assertFalse(s.value.confirmingReinstall)
        assertEquals(SignatureConflict("shm-phone", "$PREFIX.shm", Device.Phone), s.value.conflict)
        assertEquals(listOf("install:$PREFIX.shm"), installer.calls) // no uninstall
    }

    @Test
    fun confirmingUninstallsThenRetriesTheInstall() = runBlocking {
        val s = state()
        val r = runner()
        r.run(s)
        requestReinstall(s)
        r.reinstall(s)
        assertEquals(listOf("install:$PREFIX.shm", "uninstall:$PREFIX.shm", "install:$PREFIX.shm"), installer.calls)
        assertEquals(CheckStatus.Passed, s.value.items.single().status)
        assertNull(s.value.conflict)
        assertNull(s.value.errorRes)
        assertFalse(s.value.running)
    }

    @Test
    fun anUninstallFailureIsShownAndNothingIsInstalled() = runBlocking {
        installer.uninstallResult = UninstallResult.Failure("STATUS_FAILURE_ABORTED")
        val s = state()
        val r = runner()
        r.run(s)
        r.reinstall(s)
        assertEquals(listOf("install:$PREFIX.shm", "uninstall:$PREFIX.shm"), installer.calls)
        assertEquals(R.string.reinstall_error_uninstall, s.value.errorRes)
        assertEquals("STATUS_FAILURE_ABORTED", s.value.errorDetail)
        assertEquals(CheckStatus.Failed, s.value.items.single().status)
        assertFalse(s.value.running)
    }

    @Test
    fun aPackageOutsideTheBuildIsNeverUninstalled() = runBlocking {
        val s = state()
        s.value = s.value.copy(conflict = SignatureConflict("other", "com.example.other", Device.Phone))
        runner().reinstall(s)
        assertTrue(installer.calls.isEmpty())
        assertNull(s.value.conflict)

        // The item is in the list but the build does not carry it (or is for another prefix).
        s.value = s.value.copy(conflict = SignatureConflict("shm-phone", "$PREFIX.shm", Device.Phone))
        runner(built("zzz")).reinstall(s)
        assertTrue(installer.calls.isEmpty())
        assertNull(s.value.conflict)
    }

    @Test
    fun aSystemAppIsNotUninstalledEvenIfAConflictWasRecorded() = runBlocking {
        installer.kind = PackageKind.System
        val s = state()
        s.value = s.value.copy(conflict = SignatureConflict("shm-phone", "$PREFIX.shm", Device.Phone))
        runner().reinstall(s)
        assertTrue(installer.calls.isEmpty())
        assertEquals(R.string.reinstall_error_phone, s.value.errorRes)
    }

    @Test
    fun theConfirmationOpensOnlyWithAnOfferedConflict() {
        val s = state()
        requestReinstall(s)
        assertFalse(s.value.confirmingReinstall)
    }
}

class WatchReinstallTest {
    private lateinit var dir: File
    private val device = ConflictingWatch()

    @Before fun setUp() {
        dir = Files.createTempDirectory("watch-reinstall").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun built(vararg ids: String) = BuiltBuild(
        "k", PREFIX, 1, 0,
        ids.map { id -> File(dir, "$id.apk").apply { writeBytes(ByteArray(8) { 1 }) }.let { BuiltRecord(id, it.path, it.length(), "00") } },
    )

    private fun state() = MutableStateFlow(
        InstallUiState(listOf(InstallItem("shm-watch", "Health Monitor", "$PREFIX.shm", CheckStatus.Pending))),
    )

    private fun runner(build: BuiltBuild? = built("shm-watch")) = WatchInstallRunner(device, { build }, { PREFIX }, {})

    @Test
    fun theWatchPmOutputOffersTheFallbackForAUserPackage() = runBlocking {
        device.failure = InstallResult.Failure(null, "Error: INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match", InstallResult.Stage.COMMIT)
        val s = state()
        runner().run(s)
        assertEquals(SignatureConflict("shm-watch", "$PREFIX.shm", Device.Watch), s.value.conflict)
        assertEquals(R.string.reinstall_error_watch, s.value.errorRes)
        assertEquals(listOf("install:shm-watch"), device.calls)
    }

    @Test
    fun aSystemPackageGetsThePlainError() = runBlocking {
        device.kind = PackageKind.System
        val s = state()
        runner().run(s)
        assertNull(s.value.conflict)
        assertEquals(R.string.install_error_signature, s.value.errorRes)
    }

    @Test
    fun otherFailuresAreNotOffered() = runBlocking {
        device.failure = InstallResult.Failure("INSTALL_FAILED_VERSION_DOWNGRADE", "Downgrade", InstallResult.Stage.COMMIT)
        val s = state()
        runner().run(s)
        assertNull(s.value.conflict)
        assertEquals(R.string.install_error_failed, s.value.errorRes)
    }

    @Test
    fun cancellingDoesNothing() = runBlocking {
        val s = state()
        runner().run(s)
        requestReinstall(s)
        cancelReinstall(s)
        assertEquals(listOf("install:shm-watch"), device.calls)
        assertTrue(s.value.conflict != null)
    }

    @Test
    fun confirmingUninstallsThenRetriesTheInstall() = runBlocking {
        val s = state()
        val r = runner()
        r.run(s)
        requestReinstall(s)
        r.reinstall(s)
        assertEquals(listOf("install:shm-watch", "uninstall:$PREFIX.shm", "install:shm-watch"), device.calls)
        assertEquals(CheckStatus.Passed, s.value.items.single().status)
        assertNull(s.value.errorRes)
        assertNull(s.value.conflict)
    }

    @Test
    fun anUninstallFailureIsShown() = runBlocking {
        device.uninstallResult = UninstallResult.Failure("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]")
        val s = state()
        val r = runner()
        r.run(s)
        r.reinstall(s)
        assertEquals(listOf("install:shm-watch", "uninstall:$PREFIX.shm"), device.calls)
        assertEquals(R.string.reinstall_error_uninstall, s.value.errorRes)
        assertEquals("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]", s.value.errorDetail)
        assertFalse(s.value.running)
    }

    @Test
    fun aLostConnectionDuringTheUninstallIsShown() = runBlocking {
        device.uninstallThrows = AdbException.PairingRequired()
        val s = state()
        val r = runner()
        r.run(s)
        r.reinstall(s)
        assertEquals(R.string.watch_error_pairing, s.value.errorRes)
        assertEquals(CheckStatus.Failed, s.value.items.single().status)
    }

    @Test
    fun aPackageOutsideTheBuildIsNeverUninstalled() = runBlocking {
        val s = state()
        s.value = s.value.copy(conflict = SignatureConflict("other", "com.example.other", Device.Watch))
        runner().reinstall(s)
        assertTrue(device.calls.isEmpty())
        s.value = s.value.copy(conflict = SignatureConflict("shm-watch", "$PREFIX.shm", Device.Watch))
        runner(null).reinstall(s)
        assertTrue(device.calls.isEmpty())
    }
}
