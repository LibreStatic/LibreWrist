package io.openshm.patcher

import io.openshm.patcher.adb.GrantOutcome
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.PairingFailure
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.adb.ShellResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbPurpose
import io.openshm.patcher.device.PhoneInstaller
import io.openshm.patcher.device.PhoneProbe
import io.openshm.patcher.device.SettingsTarget
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.installResultFor
import io.openshm.patcher.device.isListenerEnabled
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.install.GrantRow
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.install.PhoneInstallRunner
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.installItemsFor
import io.openshm.patcher.ui.screens.install.toPhase
import java.io.File
import java.io.IOException
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
import io.openshm.patcher.engine.Grant as EngineGrant

private const val PKG = "io.openshm.u123456.bridge"

private fun engine(kind: String, vararg fields: Pair<String, String>, optional: Boolean = false) =
    EngineGrant(kind, fields.toMap(), optional, null)

private val ANSWER = Grant(GrantKind.Permission, "ANSWER_PHONE_CALLS", false, engine("permission", "name" to "android.permission.ANSWER_PHONE_CALLS"))
private val RESTRICTED = Grant(GrantKind.AppOp, "ACCESS_RESTRICTED_SETTINGS", false, engine("appop", "op" to "ACCESS_RESTRICTED_SETTINGS"))
private val OVERLAY = Grant(GrantKind.AppOp, "SYSTEM_ALERT_WINDOW", false, engine("appop", "op" to "SYSTEM_ALERT_WINDOW"))
private val LISTENER = Grant(GrantKind.NotificationListener, ".BridgeNotificationListener", false, engine("notificationListener", "component" to ".BridgeNotificationListener"))

private fun rows(vararg grants: Grant) = grants.map { GrantRow(it, CheckStatus.Pending, manual = it.kind != GrantKind.NotificationListener) }

private class FakeProbe : PhoneProbe {
    val permissions = mutableSetOf<String>()
    val appOps = mutableMapOf<String, Boolean?>()
    var listener = false
    override fun permissionGranted(pkg: String, permission: String) = permission in permissions
    override fun appOpAllowed(pkg: String, op: String): Boolean? = appOps[op]
    override fun listenerEnabled(pkg: String, component: String): Boolean {
        assertEquals("$PKG.BridgeNotificationListener", component) // resolved against the package
        return listener
    }
}

class PhoneGrantsTest {
    private val probe = FakeProbe()
    private val grants = PhoneGrants(probe)

    private fun statuses(vararg g: Grant) = grants.refresh(PKG, rows(*g)).map { it.status }

    @Test
    fun missingAccessIsPendingAndNeedsTheUser() {
        val r = rows(ANSWER, RESTRICTED, OVERLAY, LISTENER)
        assertTrue(r.all { it.needsUser })
        assertEquals(List(4) { CheckStatus.Pending }, statuses(ANSWER, RESTRICTED, OVERLAY, LISTENER))
    }

    @Test
    fun grantedAccessPasses() {
        probe.permissions += "android.permission.ANSWER_PHONE_CALLS"
        probe.appOps["SYSTEM_ALERT_WINDOW"] = true
        probe.appOps["ACCESS_RESTRICTED_SETTINGS"] = true
        probe.listener = true
        assertEquals(List(4) { CheckStatus.Passed }, statuses(ANSWER, RESTRICTED, OVERLAY, LISTENER))
    }

    @Test
    fun anEnabledListenerMakesRestrictedSettingsPassed() {
        probe.listener = true
        probe.appOps["ACCESS_RESTRICTED_SETTINGS"] = false
        assertEquals(listOf(CheckStatus.Passed, CheckStatus.Passed), statuses(RESTRICTED, LISTENER))
    }

    @Test
    fun restrictedSettingsStayPendingWhileTheListenerIsOff() {
        assertEquals(listOf(CheckStatus.Pending, CheckStatus.Pending), statuses(RESTRICTED, LISTENER))
    }

    @Test
    fun anOpThatCannotBeReadKeepsWhatWasApplied() {
        probe.appOps["SYSTEM_ALERT_WINDOW"] = null
        assertEquals(listOf(CheckStatus.Pending), statuses(OVERLAY))
        val applied = listOf(GrantRow(OVERLAY, CheckStatus.Passed, manual = true))
        assertEquals(CheckStatus.Passed, grants.refresh(PKG, applied).single().status)
    }

    @Test
    fun anOptionalMissingGrantIsAWarningAndRunningRowsAreLeftAlone() {
        val optional = ANSWER.copy(optional = true)
        assertEquals(CheckStatus.Warning, grants.refresh(PKG, rows(optional)).single().status)
        val running = listOf(GrantRow(ANSWER, CheckStatus.Running, manual = true))
        assertEquals(CheckStatus.Running, grants.refresh(PKG, running).single().status)
    }

    @Test
    fun eachGrantOpensItsOwnSettingsScreen() {
        assertEquals(SettingsTarget.AppDetails, PhoneGrants.targetFor(ANSWER))
        assertEquals(SettingsTarget.AppDetails, PhoneGrants.targetFor(RESTRICTED))
        assertEquals(SettingsTarget.Overlay, PhoneGrants.targetFor(OVERLAY))
        assertEquals(SettingsTarget.ListenerDetail, PhoneGrants.targetFor(LISTENER))
        assertEquals("$PKG.BridgeNotificationListener", PhoneGrants.componentOf(PKG, LISTENER))
        assertEquals("a.b.Full", PhoneGrants.componentOf(PKG, LISTENER.copy(engine = engine("notificationListener", "component" to "a.b.Full"))))
    }

    @Test
    fun listenerSettingIsMatchedInBothFlattenedForms() {
        val cls = "$PKG.BridgeNotificationListener"
        assertTrue(isListenerEnabled("x/y.Z:$PKG/$cls", PKG, cls))
        assertTrue(isListenerEnabled("$PKG/.BridgeNotificationListener", PKG, cls))
        assertFalse(isListenerEnabled("x/y.Z", PKG, cls))
        assertFalse(isListenerEnabled(null, PKG, cls))
    }

    @Test
    fun theRealCatalogueListsOnlyGrantsTheProbeCanHandle() {
        TestCatalogue.install()
        val real = AppCatalogue.current.grants.getValue("bridge-phone")
        assertTrue(real.any { it.kind == GrantKind.NotificationListener })
        // Every row resolves without error against the probe.
        FakeProbe().also { PhoneGrants(it).refresh(PKG, real.map { g -> GrantRow(g, CheckStatus.Pending, manual = true) }) }
    }
}

class PhoneInstallItemsTest {
    @Test
    fun phoneItemsComeInInstallOrderWithOnboardingAndUserOnlyRows() {
        TestCatalogue.install()
        val items = installItemsFor(Device.Phone)
        assertEquals(listOf("shm-phone", "bridge-phone"), items.map { it.outputId })
        assertEquals(listOf(true, false), items.map { it.onboarding })
        val bridge = items.last()
        assertTrue(bridge.grants.isNotEmpty())
        assertTrue(bridge.grants.all { it.needsUser })
        assertTrue(items.first().grants.isEmpty())
        // The watch keeps applying its grants itself.
        assertTrue(installItemsFor(Device.Watch).flatMap { it.grants }.none { it.manual })
    }
}

class InstallStatusMappingTest {
    @Test
    fun aSignatureClashIsRecognisedFromTheMessageOrTheLegacyStatus() {
        val byMessage = installResultFor(5, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match", 0) as InstallResult.Failure
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", byMessage.code)
        val byLegacy = installResultFor(5, "conflict", -7) as InstallResult.Failure
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", byLegacy.code)
    }

    @Test
    fun successPendingAndOtherFailures() {
        assertTrue(installResultFor(0, null, 1) is InstallResult.Success)
        assertNull(installResultFor(-1, null, 0)) // user action: not final
        assertEquals("INSTALL_FAILED_ABORTED", (installResultFor(3, "INSTALL_FAILED_ABORTED: user", 0) as InstallResult.Failure).code)
        assertEquals("STATUS_FAILURE_ABORTED", (installResultFor(3, null, 0) as InstallResult.Failure).code)
        assertEquals("STATUS_FAILURE_STORAGE", (installResultFor(6, null, 0) as InstallResult.Failure).code)
        assertEquals("STATUS_FAILURE", (installResultFor(1, "", 0) as InstallResult.Failure).code)
    }

    @Test
    fun selfAdbStatesMapToScreenPhases() {
        assertNull(SelfAdbState.Idle.toPhase())
        assertEquals(SelfAdbPhase.WaitingForService, SelfAdbState.WaitingForPairingService.toPhase())
        assertEquals(SelfAdbPhase.WaitingForCode, SelfAdbState.WaitingForCode(1).toPhase())
        assertEquals(SelfAdbPhase.WrongCode, SelfAdbState.WaitingForCode(1, PairingFailure.WRONG_CODE).toPhase())
        assertEquals(SelfAdbPhase.Connecting, SelfAdbState.Pairing.toPhase())
        assertEquals(SelfAdbPhase.Connecting, SelfAdbState.Connecting.toPhase())
        assertEquals(SelfAdbPhase.Ambiguous, SelfAdbState.AmbiguousService.toPhase())
    }
}

private class FakePhoneInstaller : PhoneInstaller {
    var allowed = true
    val installed = mutableListOf<String>()
    var result: (String) -> InstallResult = { InstallResult.Success(1) }
    var throws: IOException? = null
    var upToDate: (String) -> Boolean = { false }
    override fun canInstallPackages() = allowed
    override fun isUpToDate(apk: File, packageName: String) = upToDate(packageName)
    override suspend fun install(apk: File, packageName: String, onProgress: (Float) -> Unit): InstallResult {
        throws?.let { throw it }
        installed += packageName
        onProgress(1f)
        return result(packageName)
    }
    override fun packageKind(packageName: String): PackageKind = PackageKind.System // never offers a removal
    override suspend fun uninstall(packageName: String): UninstallResult = error("not used here")
}

class PhoneInstallRunnerTest {
    private lateinit var dir: File
    private val prefix = "io.openshm.u123456"
    private var locked = 0
    private val installer = FakePhoneInstaller()
    private val probe = FakeProbe()

    @Before fun setUp() {
        dir = Files.createTempDirectory("phone-install").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun built(vararg ids: String, builtPrefix: String = prefix) = BuiltBuild(
        "k", builtPrefix, 1, 0,
        ids.map { id -> File(dir, "$id.apk").apply { writeBytes(ByteArray(8) { 1 }) }.let { BuiltRecord(id, it.path, it.length(), "00") } },
    )

    private fun items() = listOf(
        InstallItem("shm-phone", "Health Monitor", "$prefix.shm", CheckStatus.Pending, onboarding = true),
        InstallItem("bridge-phone", "Bridge", "$prefix.bridge", CheckStatus.Pending, rows(ANSWER, LISTENER)),
    )

    private fun state(items: List<InstallItem> = items()) = MutableStateFlow(InstallUiState(items))

    private fun runner(build: BuiltBuild?, currentPrefix: String = prefix) =
        PhoneInstallRunner(installer, PhoneGrants(probe), { build }, { currentPrefix }, { locked++ })


    @Test
    fun installsInOrderLocksThePrefixOnceAndReadsTheGrantRows() = runBlocking {
        val s = state()
        runner(built("shm-phone", "bridge-phone")).run(s)
        assertEquals(listOf("$prefix.shm", "$prefix.bridge"), installer.installed)
        assertEquals(1, locked)
        assertFalse(s.value.running)
        assertNull(s.value.errorRes)
        assertTrue(s.value.items.all { it.status == CheckStatus.Passed })
        // Nothing granted yet: the user has work left, but the run did not block on it.
        assertTrue(s.value.items[1].grants.all { it.status == CheckStatus.Pending })
        assertFalse(s.value.done)
        assertTrue(s.value.canSkipGrants)
        assertTrue(s.value.items[0].onboarding)
    }

    @Test
    fun theScreenIsDoneOnceTheUserSwitchedEverythingOn() = runBlocking {
        val s = state()
        val r = runner(built("shm-phone", "bridge-phone"))
        r.run(s)
        probe.permissions += "android.permission.ANSWER_PHONE_CALLS"
        probe.listener = true
        r.refresh(s)
        assertTrue(s.value.done)
        assertFalse(s.value.canSkipGrants)
    }

    @Test
    fun missingOutdatedOrChangedBuildsStopBeforeInstalling() = runBlocking {
        val cases = listOf(
            built("shm-phone", "bridge-phone", builtPrefix = "io.openshm.uffffff"),
            built("shm-phone"), // no record for the bridge
            built("shm-phone", "bridge-phone").also { File(it.outputs.first().path).appendBytes(byteArrayOf(0)) },
            null,
        )
        for (build in cases) {
            val s = state()
            runner(build).run(s)
            assertEquals(R.string.install_error_build_missing, s.value.errorRes)
            assertFalse(s.value.running)
        }
        assertTrue(installer.installed.isEmpty())
        assertEquals(0, locked)
    }

    @Test
    fun withoutPermissionToInstallTheRunStopsWithTheSettingStepAndRecoversOnRefresh() = runBlocking {
        installer.allowed = false
        val s = state()
        val r = runner(built("shm-phone", "bridge-phone"))
        r.run(s)
        assertTrue(s.value.needsUnknownSources)
        assertEquals(R.string.install_error_unknown_sources, s.value.errorRes)
        assertTrue(installer.installed.isEmpty())

        r.refresh(s) // still not allowed
        assertTrue(s.value.needsUnknownSources)

        installer.allowed = true
        r.refresh(s) // screen resumed after the user allowed it
        assertFalse(s.value.needsUnknownSources)
        assertNull(s.value.errorRes)

        r.run(s)
        assertEquals(2, installer.installed.size)
    }

    @Test
    fun aSignatureMismatchStopsWithItsMessageAndNeverRemovesAnything() = runBlocking {
        installer.result = { InstallResult.Failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "signatures do not match", InstallResult.Stage.COMMIT) }
        val s = state()
        runner(built("shm-phone", "bridge-phone")).run(s)
        assertEquals(listOf("$prefix.shm"), installer.installed)
        assertEquals(R.string.install_error_signature, s.value.errorRes)
        assertTrue(s.value.errorDetail!!.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertEquals(CheckStatus.Failed, s.value.items[0].status)
        assertEquals(CheckStatus.Pending, s.value.items[1].status)
        assertEquals(0, locked)
    }

    @Test
    fun otherRefusalsAndSendErrorsUseTheGenericMessage() = runBlocking {
        installer.result = { InstallResult.Failure("STATUS_FAILURE_ABORTED", "", InstallResult.Stage.COMMIT) }
        val s = state()
        runner(built("shm-phone", "bridge-phone")).run(s)
        assertEquals(R.string.install_error_failed, s.value.errorRes)
        assertEquals("STATUS_FAILURE_ABORTED", s.value.errorDetail)

        installer.throws = IOException("disk full")
        val s2 = state()
        runner(built("shm-phone", "bridge-phone")).run(s2)
        assertEquals(R.string.install_error_failed, s2.value.errorRes)
        assertEquals(CheckStatus.Failed, s2.value.items[0].status)
    }

    @Test
    fun startingAgainDoesNotInstallPassedItemsAgain() = runBlocking {
        installer.result = { if (it.endsWith("bridge")) InstallResult.Failure("INSTALL_FAILED_OLDER_SDK", "old", InstallResult.Stage.COMMIT) else InstallResult.Success(1) }
        val s = state()
        val build = built("shm-phone", "bridge-phone")
        runner(build).run(s)
        assertEquals(CheckStatus.Passed, s.value.items[0].status)
        assertEquals(CheckStatus.Failed, s.value.items[1].status)

        installer.result = { InstallResult.Success(1) }
        runner(build).run(s)
        assertEquals(listOf("$prefix.shm", "$prefix.bridge", "$prefix.bridge"), installer.installed)
        assertNull(s.value.errorRes)
        assertTrue(s.value.items.all { it.status == CheckStatus.Passed })
    }

    @Test
    fun anItemAlreadyInstalledWithTheSameVersionAndKeyIsNotSentAgain() = runBlocking {
        installer.upToDate = { it.endsWith("shm") }
        val s = state()
        runner(built("shm-phone", "bridge-phone")).run(s)
        assertEquals(listOf("$prefix.bridge"), installer.installed)
        assertTrue(s.value.items.all { it.status == CheckStatus.Passed })
        assertEquals(1, locked)
        assertNull(s.value.errorRes)
        assertNull(s.value.items[0].progress)
    }

    @Test
    fun whenEverythingIsUpToDateOnlyTheGrantRowsAreRead() = runBlocking {
        installer.upToDate = { true }
        probe.permissions += "android.permission.ANSWER_PHONE_CALLS"
        probe.listener = true
        val s = state()
        runner(built("shm-phone", "bridge-phone")).run(s)
        assertTrue(installer.installed.isEmpty())
        assertTrue(s.value.done)
        assertEquals(1, locked)
    }

    @Test
    fun selfAdbGrantsEverythingThatIsMissingThenReadsTheRowsAgain() = runBlocking {
        val s = state()
        val r = runner(built("shm-phone", "bridge-phone"))
        r.run(s)
        val calls = mutableListOf<Pair<String, List<AdbGrant>>>()
        val ok = r.grantWithAdb(s) { pkg, grants ->
            calls += pkg to grants
            // What the shell did shows in the system afterwards.
            probe.permissions += "android.permission.ANSWER_PHONE_CALLS"
            probe.listener = true
            GrantReport(grants.map { GrantOutcome(it, "cmd", ShellResult(0, "", "")) })
        }
        assertTrue(ok)
        assertEquals(listOf("$prefix.bridge"), calls.map { it.first }) // shm-phone has no grants
        assertEquals(
            listOf(AdbGrant.Permission("android.permission.ANSWER_PHONE_CALLS", false), AdbGrant.NotificationListener(".BridgeNotificationListener", false)),
            calls.single().second,
        )
        assertTrue(s.value.done)
        assertFalse(s.value.running)
    }

    @Test
    fun selfAdbSkipsPackagesThatAreNotInstalledAndReportsAFailedRequiredGrant() = runBlocking {
        val s = state(items().map { it.copy(status = if (it.outputId == "shm-phone") CheckStatus.Passed else CheckStatus.Pending) })
        val r = runner(null)
        var called = 0
        assertTrue(r.grantWithAdb(s) { _, _ -> called++; GrantReport(emptyList()) })
        assertEquals(0, called)

        val s2 = state(items().map { it.copy(status = CheckStatus.Passed) })
        val ok = r.grantWithAdb(s2) { _, grants ->
            GrantReport(grants.map { GrantOutcome(it, "cmd", ShellResult(1, "", "denied")) })
        }
        assertFalse(ok)
        assertEquals(R.string.install_error_grant, s2.value.errorRes)
        // The rows are read from the system afterwards, so a refused grant shows as still missing.
        assertEquals(CheckStatus.Pending, s2.value.items[1].grants[0].status)
        assertFalse(s2.value.running)
    }

    @Test
    fun aDroppedSelfAdbConnectionLeavesTheRowsPendingForAnotherTry() = runBlocking {
        val s = state(items().map { it.copy(status = CheckStatus.Passed) })
        val ok = runner(null).grantWithAdb(s) { _, _ -> throw io.openshm.patcher.adb.AdbException.NotConnected() }
        assertFalse(ok)
        assertEquals(R.string.install_error_grant, s.value.errorRes)
        assertTrue(s.value.items[1].grants.all { it.status == CheckStatus.Pending })
    }
}

class SelfAdbControllerTest {
    private val client = io.openshm.patcher.adb.AdbClient(object : io.openshm.patcher.adb.AdbTransport {
        override val isConnected = true
        override fun open(destination: String): io.openshm.patcher.adb.AdbChannel = throw UnsupportedOperationException()
        override fun close() {}
    })

    @Before @After fun reset() = SelfAdbController.finish()

    @Test
    fun anAttemptIsActiveUntilFinishedAndOnlyOneRunsAtATime() {
        assertFalse(SelfAdbController.state.value.active)
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.PhoneGrants))
        assertFalse(SelfAdbController.begin(SelfAdbPurpose.PhoneGrants))
        assertEquals(SelfAdbPhase.WaitingForService, SelfAdbController.state.value.phase)
        SelfAdbController.finish()
        assertFalse(SelfAdbController.state.value.active)
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.PhoneGrants))
    }

    @Test
    fun sessionStatesAreFollowedAndIdleIsIgnored() {
        SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)
        SelfAdbController.onSession(SelfAdbState.Idle)
        assertEquals(SelfAdbPhase.WaitingForService, SelfAdbController.state.value.phase)
        SelfAdbController.onSession(SelfAdbState.WaitingForCode(1))
        assertEquals(SelfAdbPhase.WaitingForCode, SelfAdbController.state.value.phase)
        SelfAdbController.onSession(SelfAdbState.Connected(client))
        assertEquals(SelfAdbPhase.Granting, SelfAdbController.state.value.phase)
        assertTrue(SelfAdbController.state.value.active)
    }

    @Test
    fun theConnectionIsHandedOutOnceAndOnlyWhenConnected() {
        SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)
        assertNull(SelfAdbController.takeClient(SelfAdbPurpose.PhoneGrants))
        SelfAdbController.onSession(SelfAdbState.Connected(client))
        assertTrue(SelfAdbController.takeClient(SelfAdbPurpose.PhoneGrants) === client)
        assertNull(SelfAdbController.takeClient(SelfAdbPurpose.PhoneGrants))
    }

    @Test
    fun aFailureEndsTheAttemptAndLateStatesDoNotReviveIt() {
        SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)
        SelfAdbController.onSession(SelfAdbState.Failed(io.openshm.patcher.adb.AdbException.Discovery("no service")))
        val s = SelfAdbController.state.value
        assertEquals(SelfAdbPhase.Failed, s.phase)
        assertEquals("no service", s.error)
        assertFalse(s.active)
        SelfAdbController.onSession(SelfAdbState.Connecting)
        assertEquals(SelfAdbPhase.Failed, SelfAdbController.state.value.phase)
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)) // retry is allowed
    }

    @Test
    fun missingNotificationAccessIsReportedAsSuch() {
        SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)
        SelfAdbController.fail("no permission", needsNotifications = true)
        assertTrue(SelfAdbController.state.value.needsNotifications)
        assertFalse(SelfAdbController.state.value.active)
    }

    @Test
    fun theCodeGoesToTheSessionTheServiceRegistered() {
        val got = mutableListOf<String>()
        SelfAdbController.setCodeSink { got += it }
        SelfAdbController.submitCode("123456")
        SelfAdbController.setCodeSink(null)
        SelfAdbController.submitCode("654321")
        assertEquals(listOf("123456"), got)
    }
}
