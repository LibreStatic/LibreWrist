package io.openshm.patcher

import io.openshm.patcher.adb.AdbChannel
import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.AdbTransport
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.device.AdbPhoneInstaller
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.PhoneInstaller
import io.openshm.patcher.device.PhoneProbe
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbPurpose
import io.openshm.patcher.device.SelfAdbSnapshot
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.SetupFlow
import io.openshm.patcher.ui.nav.SkippedStep
import io.openshm.patcher.ui.nav.automationSkippedRoutes
import io.openshm.patcher.ui.nav.skippedRunAfter
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.install.PhoneInstallRunner
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.phoneadb.PhoneAdbUiState
import io.openshm.patcher.ui.screens.phoneadb.grantOwnPermissions
import io.openshm.patcher.ui.screens.phoneadb.ownPermissionsToGrant
import io.openshm.patcher.ui.screens.phoneadb.reduce
import java.io.ByteArrayOutputStream
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

/** An ADB transport that answers `shell,v2,raw:` through [shell] and records the install-writes. */
private class ScriptedDevice(val shell: (String) -> Triple<String, String, Int> = { Triple("", "", 0) }) : AdbTransport {
    val shells = mutableListOf<String>()
    val writes = mutableListOf<String>()
    override val isConnected = true
    override fun close() {}

    private fun frames(stdout: String, stderr: String, exit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun frame(id: Int, payload: ByteArray) {
            out.write(id)
            for (shift in 0..24 step 8) out.write((payload.size shr shift) and 0xff)
            out.write(payload)
        }
        if (stdout.isNotEmpty()) frame(1, stdout.toByteArray())
        if (stderr.isNotEmpty()) frame(2, stderr.toByteArray())
        frame(3, byteArrayOf(exit.toByte()))
        return out.toByteArray()
    }

    private fun channel(input: ByteArray) = object : AdbChannel {
        var pos = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= input.size) return -1
            val n = minOf(length, input.size - pos)
            System.arraycopy(input, pos, buffer, offset, n)
            pos += n
            return n
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {}
        override fun close() {}
    }

    override fun open(destination: String): AdbChannel {
        val command = destination.removePrefix("shell,v2,raw:")
        if (command.startsWith("cmd package install-write")) {
            writes += command
            return channel(frames("Success: streamed 8 bytes\n", "", 0))
        }
        shells += command
        val (out, err, exit) = when {
            command.startsWith("cmd package install-create") -> Triple("Success: created install session [42]\n", "", 0)
            command.startsWith("cmd package install-commit") -> Triple("Success\n", "", 0)
            else -> shell(command)
        }
        return channel(frames(out, err, exit))
    }
}

private class RecordingPlatform : PhoneInstaller {
    val calls = mutableListOf<String>()
    var listeners = listOf("a.b.listener")
    override fun canInstallPackages() = false
    override fun isUpToDate(apk: File, packageName: String): Boolean = true.also { calls += "upToDate:$packageName" }
    override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult =
        error("the platform installer must not install")
    override fun packageKind(packageName: String) = PackageKind.User.also { calls += "kind:$packageName" }
    override fun startBridge(packageName: String, receiver: String, action: String) {
        calls += "start:$packageName"
    }
    override suspend fun uninstall(packageName: String): UninstallResult = error("the platform installer must not uninstall")
    override fun notificationListenerPackages() = listeners
}

class AdbPhoneInstallerTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("adb-phone").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun apk() = File(dir, "x.apk").apply { writeBytes(ByteArray(8) { 1 }) }

    @Test
    fun installGoesThroughTheInstallSessionCommandsAndReportsProgress() = runBlocking {
        val device = ScriptedDevice()
        val platform = RecordingPlatform()
        val progress = mutableListOf<Float>()
        val result = AdbPhoneInstaller(AdbClient(device), platform).install(apk(), "a.b.c") { progress += it }
        assertTrue(result is InstallResult.Success)
        assertTrue(device.shells.first().startsWith("cmd package install-create -S 8 -r"))
        assertEquals(listOf("cmd package install-write -S 8 42 base.apk -"), device.writes)
        assertEquals("cmd package install-commit 42", device.shells.last())
        assertEquals(1f, progress.last(), 0f)
    }

    @Test
    fun uninstallRunsPmUninstallAndMapsTheResult() = runBlocking {
        val device = ScriptedDevice { cmd -> if (cmd.endsWith("a.b.gone")) Triple("Failure [DELETE_FAILED_INTERNAL_ERROR]\n", "", 1) else Triple("Success\n", "", 0) }
        val installer = AdbPhoneInstaller(AdbClient(device), RecordingPlatform())
        assertEquals(UninstallResult.Success, installer.uninstall("a.b.c"))
        val failure = installer.uninstall("a.b.gone")
        assertTrue(failure is UninstallResult.Failure)
        assertEquals("Failure [DELETE_FAILED_INTERNAL_ERROR]", (failure as UninstallResult.Failure).message)
        assertEquals(listOf("pm uninstall --user 0 a.b.c", "pm uninstall --user 0 a.b.gone"), device.shells)
    }

    @Test
    fun noPermissionToInstallIsNeededAndQueriesGoToThePlatform() {
        val platform = RecordingPlatform()
        val installer = AdbPhoneInstaller(AdbClient(ScriptedDevice()), platform)
        assertTrue(installer.canInstallPackages())
        assertTrue(installer.isUpToDate(apk(), "a.b.c"))
        assertEquals(PackageKind.User, installer.packageKind("a.b.c"))
        installer.startBridge("a.b.c", "r", "act")
        assertEquals(listOf("a.b.listener"), installer.notificationListenerPackages())
        assertEquals(listOf("upToDate:a.b.c", "kind:a.b.c", "start:a.b.c"), platform.calls)
    }
}

class PhoneInstallRunnerOverAdbTest {
    private lateinit var dir: File
    private val prefix = "io.openshm.u123456"
    private val old = "io.openshm.u98ff48"

    @Before fun setUp() {
        dir = Files.createTempDirectory("phone-adb-run").toFile()
        io.openshm.patcher.data.AppCatalogue.install(TestCatalogue.load())
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private class NoProbe : PhoneProbe {
        override fun batteryExempt(pkg: String): Boolean? = null
        override fun permissionGranted(pkg: String, permission: String) = false
        override fun appOpAllowed(pkg: String, op: String): Boolean? = null
        override fun listenerEnabled(pkg: String, component: String) = false
    }

    private fun built() = BuiltBuild(
        "k", prefix, 1, 0,
        listOf("shm-phone").map { id -> File(dir, "$id.apk").apply { writeBytes(ByteArray(8) { 1 }) }.let { BuiltRecord(id, it.path, it.length(), "00") } },
    )

    private fun state() = MutableStateFlow(InstallUiState(listOf(InstallItem("shm-phone", "Health Monitor", "$prefix.shm", CheckStatus.Pending))))

    @Test
    fun withTheAdbInstallerTheUnknownSourcesStopDoesNotApplyAndEverythingGoesThroughIt() = runBlocking {
        val rec = RecordingPlatform().apply { listeners = listOf("$old.shmbridge.phone", "$prefix.shmbridge.phone") }
        val platform = object : PhoneInstaller by rec {
            override fun isUpToDate(apk: File, packageName: String) = false
        }
        val device = ScriptedDevice()
        val outputs = listOf(io.openshm.patcher.data.OutputApp("bridge-phone", "Bridge", io.openshm.patcher.data.Device.Phone, "\${PKG_PREFIX}.shmbridge.phone", null, singleCopy = true))
        val runner = PhoneInstallRunner(platform, PhoneGrants(NoProbe()), { built() }, { prefix }, {}, {}, outputs, "io.openshm.patcher")
        runner.installerOverride = AdbPhoneInstaller(AdbClient(device), platform)
        val s = state()
        runner.run(s)
        assertFalse(s.value.needsUnknownSources)
        assertNull(s.value.errorRes)
        assertEquals(CheckStatus.Passed, s.value.items.single().status)
        assertTrue(device.shells.any { it.startsWith("cmd package install-create") })
        // The bridge of an earlier prefix goes through the shell too, with no system dialog.
        assertEquals(listOf("pm uninstall --user 0 $old.shmbridge.phone"), device.shells.filter { it.startsWith("pm uninstall") })
    }

    @Test
    fun withoutTheOverrideThePlatformInstallerStillStopsOnUnknownSources() = runBlocking {
        val platform = RecordingPlatform() // canInstallPackages() is false
        val runner = PhoneInstallRunner(platform, PhoneGrants(NoProbe()), { built() }, { prefix }, {})
        val s = state()
        runner.run(s)
        assertTrue(s.value.needsUnknownSources)
    }
}

class PhoneAdbStepTest {
    @Before @After fun reset() = SelfAdbController.finish()

    @Test
    fun theStepFollowsWelcomeAndIsPassedOverWithoutUnattendedMode() {
        assertEquals(Route.PhoneAdb, SetupFlow.next(Route.Welcome))
        assertEquals(Route.Profile, SetupFlow.next(Route.PhoneAdb))
        assertEquals(emptySet<Route>(), automationSkippedRoutes(true))
        assertEquals(Route.PhoneAdb, SetupFlow.next(Route.Welcome, automationSkippedRoutes(true)))
        assertEquals(Route.Profile, SetupFlow.next(Route.Welcome, automationSkippedRoutes(false)))
    }

    @Test
    fun theRunOfSkippedStepsIsAnnouncedPastThePhoneAdbStep() {
        val skipped = listOf(SkippedStep(Route.Profile, 0), SkippedStep(Route.WearablePairing, 0))
        assertEquals(listOf(Route.Profile, Route.WearablePairing), skippedRunAfter(Route.Welcome, skipped).map { it.route })
        assertEquals(listOf(Route.Profile, Route.WearablePairing), skippedRunAfter(Route.PhoneAdb, skipped).map { it.route })
        assertTrue(skippedRunAfter(Route.Welcome, listOf(SkippedStep(Route.WearablePairing, 0))).isEmpty())
    }

    @Test
    fun theStateFollowsOnlyItsOwnAttempt() {
        val own = SelfAdbSnapshot(SelfAdbPurpose.Setup, SelfAdbPhase.WaitingForCode)
        assertEquals(SelfAdbPhase.WaitingForCode, PhoneAdbUiState().reduce(own).phase)
        val other = SelfAdbSnapshot(SelfAdbPurpose.PhoneGrants, SelfAdbPhase.WaitingForCode)
        assertNull(PhoneAdbUiState(phase = SelfAdbPhase.Reconnecting).reduce(other).phase)
        assertNull(PhoneAdbUiState(phase = SelfAdbPhase.Reconnecting).reduce(SelfAdbSnapshot()).phase)
    }

    @Test
    fun aFailureKeepsItsMessageAndTheNotificationFlag() {
        val failed = SelfAdbSnapshot(SelfAdbPurpose.Setup, SelfAdbPhase.Failed, error = "boom", needsNotifications = true)
        val state = PhoneAdbUiState().reduce(failed)
        assertEquals(SelfAdbPhase.Failed, state.phase)
        assertEquals("boom", state.error)
        assertTrue(state.needsNotifications)
        assertNull(PhoneAdbUiState().reduce(SelfAdbSnapshot(SelfAdbPurpose.Setup, SelfAdbPhase.Connecting)).error)
    }

    @Test
    fun theSetupAttemptIsHandedOutOnlyToItsOwner() {
        val client = AdbClient(ScriptedDevice())
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.Setup))
        SelfAdbController.onSession(SelfAdbState.Connected(client))
        assertNull(SelfAdbController.takeClient(SelfAdbPurpose.PhoneGrants))
        assertTrue(SelfAdbController.takeClient(SelfAdbPurpose.Setup) === client)
        SelfAdbController.finishIfOwned(SelfAdbPurpose.Setup)
        assertFalse(SelfAdbController.state.value.active)
    }

    @Test
    fun onlyDeclaredUngrantedPermissionsExistingOnThisAndroidAreGranted() {
        val declared = setOf("android.permission.BLUETOOTH_CONNECT", "android.permission.POST_NOTIFICATIONS", "android.permission.INTERNET")
        assertEquals(
            listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.POST_NOTIFICATIONS"),
            ownPermissionsToGrant(declared, 34) { false },
        )
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), ownPermissionsToGrant(declared, 31) { false })
        assertEquals(
            listOf("android.permission.POST_NOTIFICATIONS"),
            ownPermissionsToGrant(declared, 34) { it == "android.permission.BLUETOOTH_CONNECT" },
        )
        assertTrue(ownPermissionsToGrant(setOf("android.permission.INTERNET"), 34) { false }.isEmpty())
        assertTrue(ownPermissionsToGrant(declared, 30) { false }.isEmpty())
    }

    @Test
    fun aRefusedGrantIsLoggedAndTheOthersStillRun() = runBlocking {
        val device = ScriptedDevice { cmd -> if (cmd.endsWith("POST_NOTIFICATIONS")) Triple("", "denied", 1) else Triple("", "", 0) }
        val log = mutableListOf<String>()
        val granted = grantOwnPermissions(
            AdbClient(device), "io.openshm.patcher",
            listOf("android.permission.POST_NOTIFICATIONS", "android.permission.BLUETOOTH_CONNECT"), log::add,
        )
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), granted)
        assertEquals(1, log.size)
        assertEquals(
            listOf("pm grant io.openshm.patcher android.permission.POST_NOTIFICATIONS", "pm grant io.openshm.patcher android.permission.BLUETOOTH_CONNECT"),
            device.shells,
        )
    }
}
