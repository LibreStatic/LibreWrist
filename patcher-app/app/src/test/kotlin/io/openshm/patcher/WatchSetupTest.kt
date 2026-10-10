package io.openshm.patcher

import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.adb.GrantOutcome
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.PairingFailure
import io.openshm.patcher.adb.ShellResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.data.StockApp
import io.openshm.patcher.data.Device
import io.openshm.patcher.device.UnsupportedGrantException
import io.openshm.patcher.device.WatchDevice
import io.openshm.patcher.device.toAdbGrant
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.disable.DisableStockUiState
import io.openshm.patcher.ui.screens.disable.StockAppChoice
import io.openshm.patcher.ui.screens.disable.StockOutcome
import io.openshm.patcher.ui.screens.disable.disableSelected
import io.openshm.patcher.ui.screens.install.GrantRow
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.install.WatchInstallRunner
import io.openshm.patcher.ui.screens.watchpair.WatchPairEvent
import io.openshm.patcher.ui.screens.watchpair.WatchPairUiState
import io.openshm.patcher.ui.screens.watchpair.foundWatchAddress
import io.openshm.patcher.ui.screens.watchpair.pairErrorRes
import io.openshm.patcher.ui.screens.watchpair.parseAddress
import io.openshm.patcher.ui.screens.watchpair.reduce
import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.engine.Grant as EngineGrant

private fun engineGrant(kind: String, vararg fields: Pair<String, String>, optional: Boolean = false) =
    EngineGrant(kind, fields.toMap(), optional, null)

private fun grant(kind: GrantKind, name: String, engine: EngineGrant?, optional: Boolean = engine?.optional ?: false) =
    Grant(kind, name, optional, engine)

class GrantMappingTest {
    @Test
    fun everyKindMapsToTheAdbGrant() {
        assertEquals(
            AdbGrant.Permission("android.permission.POST_NOTIFICATIONS", false),
            grant(GrantKind.Permission, "POST_NOTIFICATIONS", engineGrant("permission", "name" to "android.permission.POST_NOTIFICATIONS")).toAdbGrant(),
        )
        assertEquals(
            AdbGrant.AppOp("SCHEDULE_EXACT_ALARM", "allow", true),
            grant(GrantKind.AppOp, "SCHEDULE_EXACT_ALARM", engineGrant("appop", "op" to "SCHEDULE_EXACT_ALARM", "mode" to "allow", optional = true)).toAdbGrant(),
        )
        assertEquals(
            AdbGrant.NotificationListener("p/.Listener", false),
            grant(GrantKind.NotificationListener, "p/.Listener", engineGrant("notificationListener", "component" to "p/.Listener")).toAdbGrant(),
        )
        assertEquals(
            AdbGrant.DeviceIdleWhitelist(false),
            grant(GrantKind.BatteryExemption, "deviceIdleWhitelist", engineGrant("deviceIdleWhitelist")).toAdbGrant(),
        )
        assertEquals(
            AdbGrant.SecureSetting("some_key", "1", false),
            grant(GrantKind.SecureSetting, "some_key", engineGrant("secureSetting", "key" to "some_key", "value" to "1")).toAdbGrant(),
        )
    }

    @Test
    fun anAppOpWithoutModeDefaultsToAllow() {
        val g = grant(GrantKind.AppOp, "X", engineGrant("appop", "op" to "X")).toAdbGrant() as AdbGrant.AppOp
        assertEquals("allow", g.mode)
    }

    @Test
    fun unknownKindMissingFieldAndMissingEntryAreRejected() {
        for (bad in listOf(
            grant(GrantKind.Permission, "x", engineGrant("teleport")),
            grant(GrantKind.Permission, "x", engineGrant("permission")),
            grant(GrantKind.Permission, "x", null),
        )) {
            try {
                bad.toAdbGrant()
                org.junit.Assert.fail("expected a rejection")
            } catch (e: UnsupportedGrantException) {
            }
        }
    }

    @Test
    fun theRealCatalogueGrantsAllMap() {
        TestCatalogue.install()
        val all = io.openshm.patcher.data.AppCatalogue.current.grants.values.flatten()
        assertTrue(all.isNotEmpty())
        all.forEach { it.toAdbGrant() }
    }
}

private class FakeDevice : WatchDevice {
    val installed = mutableListOf<String>()
    val grantCalls = mutableListOf<Pair<String, List<AdbGrant>>>()
    var installFor: (File) -> InstallResult = { InstallResult.Success(1) }
    var installThrows: java.io.IOException? = null
    var failGrant: (AdbGrant) -> Boolean = { false }
    val present = mutableSetOf<String>()
    val disabled = mutableListOf<String>()
    var disableThrows: (String) -> java.io.IOException? = { null }

    override suspend fun install(apk: File, onProgress: (Float) -> Unit): InstallResult {
        installThrows?.let { throw it }
        installed += apk.name
        onProgress(1f)
        return installFor(apk)
    }

    override suspend fun applyGrants(pkg: String, grants: List<AdbGrant>): GrantReport {
        grantCalls += pkg to grants
        val outcomes = ArrayList<GrantOutcome>()
        for (g in grants) {
            val failed = failGrant(g)
            outcomes += GrantOutcome(g, "cmd", ShellResult(if (failed) 1 else 0, "", if (failed) "denied" else ""))
            if (failed && !g.optional) break
        }
        return GrantReport(outcomes)
    }

    override suspend fun isInstalled(pkg: String) = pkg in present

    override suspend fun disable(pkg: String) {
        disableThrows(pkg)?.let { throw it }
        disabled += pkg
    }
}

class WatchInstallRunnerTest {
    private lateinit var dir: File
    private val prefix = "io.openshm.u123456"
    private var locked = 0

    @Before fun setUp() {
        dir = Files.createTempDirectory("watch-install").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(id: String, bytes: Int = 8): File = File(dir, "$id.apk").apply { writeBytes(ByteArray(bytes) { 1 }) }

    private fun built(vararg ids: String, builtPrefix: String = prefix) = BuiltBuild(
        "k", builtPrefix, 1, 0,
        ids.map { id -> file(id).let { BuiltRecord(id, it.path, it.length(), "00") } },
    )

    private val required = grant(GrantKind.Permission, "A", engineGrant("permission", "name" to "p.A"))
    private val optionalGrant = grant(GrantKind.AppOp, "B", engineGrant("appop", "op" to "B", optional = true))

    private fun item(id: String, vararg grants: Grant, status: CheckStatus = CheckStatus.Pending) =
        InstallItem(id, id, "$prefix.$id", status, grants.map { GrantRow(it, CheckStatus.Pending) })

    private fun state(vararg items: InstallItem) = MutableStateFlow(InstallUiState(items.toList()))

    private fun runner(device: WatchDevice, build: BuiltBuild?, currentPrefix: String = prefix) =
        WatchInstallRunner(device, { build }, { currentPrefix }, { locked++ })

    @Test
    fun installsInOrderAppliesGrantsAndLocksThePrefix() = runBlocking {
        val device = FakeDevice()
        val s = state(item("a", required, optionalGrant), item("b"))
        runner(device, built("a", "b")).run(s)
        assertEquals(listOf("a.apk", "b.apk"), device.installed)
        assertEquals(listOf("$prefix.a"), device.grantCalls.map { it.first })
        assertTrue(s.value.done)
        assertFalse(s.value.running)
        assertNull(s.value.errorRes)
        assertEquals(1, locked)
    }

    @Test
    fun missingOutdatedOrChangedBuildsStopBeforeInstalling() = runBlocking {
        val cases = listOf(
            built("a", builtPrefix = "io.openshm.uffffff"), // other prefix
            built("zzz"), // no record for the output
            built("a").also { File(it.outputs.single().path).appendBytes(byteArrayOf(0)) }, // size changed
            null,
        )
        for (build in cases) {
            val device = FakeDevice()
            val s = state(item("a"))
            runner(device, build).run(s)
            assertEquals(R.string.install_error_build_missing, s.value.errorRes)
            assertTrue(device.installed.isEmpty())
            assertFalse(s.value.running)
        }
        assertEquals(0, locked)
    }

    @Test
    fun anInstallFailureStopsWithItsMessageAndLocksNothing() = runBlocking {
        val device = FakeDevice().apply {
            installFor = { InstallResult.Failure("INSTALL_FAILED_UPDATE_INCOMPATIBLE", "signatures do not match", InstallResult.Stage.COMMIT) }
        }
        val s = state(item("a"), item("b"))
        runner(device, built("a", "b")).run(s)
        assertEquals(listOf("a.apk"), device.installed)
        assertEquals(R.string.install_error_signature, s.value.errorRes)
        assertTrue(s.value.errorDetail!!.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertEquals(CheckStatus.Failed, s.value.items[0].status)
        assertEquals(CheckStatus.Pending, s.value.items[1].status)
        assertEquals(0, locked)
    }

    @Test
    fun otherInstallFailuresUseTheGenericMessage() = runBlocking {
        val device = FakeDevice().apply { installFor = { InstallResult.Failure("INSTALL_FAILED_OLDER_SDK", "too old", InstallResult.Stage.COMMIT) } }
        val s = state(item("a"))
        runner(device, built("a")).run(s)
        assertEquals(R.string.install_error_failed, s.value.errorRes)
    }

    @Test
    fun aConnectionFailureReportsTheWatchUnreachable() = runBlocking {
        val device = FakeDevice().apply { installThrows = AdbException.ConnectionFailed(ConnectException()) }
        val s = state(item("a"))
        runner(device, built("a")).run(s)
        assertEquals(R.string.watch_error_unreachable, s.value.errorRes)
        assertEquals(CheckStatus.Failed, s.value.items[0].status)
        assertFalse(s.value.running)
    }

    @Test
    fun aFailedRequiredGrantStopsTheRun() = runBlocking {
        val device = FakeDevice().apply { failGrant = { it is AdbGrant.Permission } }
        val s = state(item("a", required, optionalGrant), item("b"))
        runner(device, built("a", "b")).run(s)
        assertEquals(listOf("a.apk"), device.installed)
        assertEquals(R.string.install_error_grant, s.value.errorRes)
        assertEquals(CheckStatus.Passed, s.value.items[0].status)
        assertEquals(CheckStatus.Failed, s.value.items[0].grants[0].status)
        assertEquals(CheckStatus.Pending, s.value.items[0].grants[1].status)
        assertFalse(s.value.done)
        assertEquals(1, locked)
    }

    @Test
    fun aFailedOptionalGrantIsAWarningAndTheRunContinues() = runBlocking {
        val device = FakeDevice().apply { failGrant = { it is AdbGrant.AppOp } }
        val s = state(item("a", required, optionalGrant), item("b"))
        runner(device, built("a", "b")).run(s)
        assertEquals(listOf("a.apk", "b.apk"), device.installed)
        assertEquals(CheckStatus.Passed, s.value.items[0].grants[0].status)
        assertEquals(CheckStatus.Warning, s.value.items[0].grants[1].status)
        assertNull(s.value.errorRes)
        assertTrue(s.value.done)
    }

    @Test
    fun anUnknownRequiredGrantKindFailsTheRunButAnOptionalOneDoesNot() = runBlocking {
        val unknown = grant(GrantKind.Permission, "x", engineGrant("teleport"))
        val unknownOptional = grant(GrantKind.Permission, "y", engineGrant("teleport", optional = true))
        val device = FakeDevice()
        val s = state(item("a", unknown))
        runner(device, built("a")).run(s)
        assertEquals(R.string.install_error_grant, s.value.errorRes)
        assertEquals(CheckStatus.Failed, s.value.items[0].grants[0].status)
        assertTrue(device.grantCalls.isEmpty())

        val s2 = state(item("a", unknownOptional, required))
        runner(device, built("a")).run(s2)
        assertNull(s2.value.errorRes)
        assertEquals(CheckStatus.Warning, s2.value.items[0].grants[0].status)
        assertEquals(CheckStatus.Passed, s2.value.items[0].grants[1].status)
    }

    @Test
    fun startingAgainResumesWithoutReinstallingOrRegrantingPassedWork() = runBlocking {
        val device = FakeDevice().apply { failGrant = { it is AdbGrant.Permission } }
        val s = state(item("a", required), item("b"))
        val build = built("a", "b")
        runner(device, build).run(s)
        assertEquals(listOf("a.apk"), device.installed)

        device.failGrant = { false }
        runner(device, build).run(s)
        assertEquals(listOf("a.apk", "b.apk"), device.installed) // a is not sent again
        assertEquals(2, device.grantCalls.size) // a's failed grant retried once more
        assertTrue(s.value.done)

        runner(device, build).run(s) // everything passed: nothing happens
        assertEquals(listOf("a.apk", "b.apk"), device.installed)
        assertEquals(2, device.grantCalls.size)
    }

    @Test
    fun aResumedRunOnlyChecksTheFilesStillNeeded() = runBlocking {
        val device = FakeDevice()
        val s = state(item("a", status = CheckStatus.Passed), item("b"))
        runner(device, built("b")).run(s)
        assertEquals(listOf("b.apk"), device.installed)
        assertNull(s.value.errorRes)
    }
}

class DisableStockRunTest {
    private fun app(pkg: String) = StockApp(Device.Watch, pkg, pkg, false, "")
    private fun state(vararg pkgs: String, selected: Boolean = true) =
        MutableStateFlow(DisableStockUiState(pkgs.map { StockAppChoice(app(it), selected) }))

    @Test
    fun installedAppsAreDisabledAndAbsentOnesSkipped() = runBlocking {
        val device = FakeDevice().apply { present += "p.one" }
        val s = state("p.one", "p.two")
        disableSelected(device, s)
        assertEquals(listOf("p.one"), device.disabled)
        assertEquals(listOf(StockOutcome.Disabled, StockOutcome.NotInstalled), s.value.choices.map { it.outcome })
        assertTrue(s.value.done)
        assertFalse(s.value.running)
    }

    @Test
    fun unselectedAppsAreLeftAlone() = runBlocking {
        val device = FakeDevice().apply { present += "p.one" }
        val s = state("p.one", selected = false)
        disableSelected(device, s)
        assertTrue(device.disabled.isEmpty())
        assertEquals(StockOutcome.None, s.value.choices.single().outcome)
    }

    @Test
    fun aRefusedAppIsMarkedFailedAndTheStepIsNotDone() = runBlocking {
        val device = FakeDevice().apply {
            present += listOf("p.one", "p.two")
            disableThrows = { if (it == "p.one") AdbException.CommandFailed("pm", ShellResult(1, "", "no")) else null }
        }
        val s = state("p.one", "p.two")
        disableSelected(device, s)
        assertEquals(listOf("p.two"), device.disabled)
        assertEquals(StockOutcome.Failed, s.value.choices[0].outcome)
        assertFalse(s.value.done)
        assertNotNull(s.value.errorRes)
    }

    @Test
    fun aConnectionFailureStopsTheRun() = runBlocking {
        val device = FakeDevice().apply {
            present += listOf("p.one", "p.two")
            disableThrows = { AdbException.ConnectionFailed() }
        }
        val s = state("p.one", "p.two")
        disableSelected(device, s)
        assertEquals(R.string.watch_error_unreachable, s.value.errorRes)
        assertTrue(device.disabled.isEmpty())
        assertFalse(s.value.done)
        assertFalse(s.value.running)
    }
}

class WatchPairLogicTest {
    @Test
    fun addressesNeedAHostAndAPortInRange() {
        assertEquals("192.0.2.17" to 37115, parseAddress("192.0.2.17:37115"))
        assertEquals("watch.local" to 1, parseAddress(" watch.local:1 "))
        listOf("", "192.0.2.17", "192.0.2.17:", ":37115", "192.0.2.17:0", "192.0.2.17:70000", "a b:12").forEach { assertNull(it, parseAddress(it)) }
    }

    @Test
    fun theWatchIsTheFirstServiceThatIsNotThisPhone() {
        fun svc(host: String, port: Int) = AdbService("n$port", host, port, AdbServiceType.PAIRING)
        val own = { host: String -> host == "192.0.2.2" }
        assertNull(foundWatchAddress(emptyList(), own))
        assertNull(foundWatchAddress(listOf(svc("192.0.2.2", 1)), own))
        assertEquals("192.0.2.9:7", foundWatchAddress(listOf(svc("192.0.2.2", 1), svc("192.0.2.9", 7), svc("192.0.2.10", 8)), own))
    }

    @Test
    fun editingTheAddressValidatesIt() {
        val ok = WatchPairUiState(code = "123456").reduce(WatchPairEvent.AddressChanged("192.0.2.9:37115"))
        assertNull(ok.errorRes)
        assertTrue(ok.canPair)
        val bad = ok.reduce(WatchPairEvent.AddressChanged("192.0.2.9"))
        assertEquals(R.string.watchpair_error_bad_address, bad.errorRes)
        assertFalse(bad.canPair)
        assertNull(bad.reduce(WatchPairEvent.AddressChanged("")).errorRes)
    }

    @Test
    fun pairingFailuresMapToTheirMessages() {
        fun res(r: PairingFailure) = pairErrorRes(AdbException.Pairing(r))
        assertEquals(R.string.watchpair_error_wrong_code, res(PairingFailure.WRONG_CODE))
        assertEquals(R.string.watchpair_error_timeout, res(PairingFailure.TIMEOUT))
        assertEquals(R.string.watchpair_error_network, res(PairingFailure.NETWORK))
        assertEquals(R.string.watchpair_error_other, res(PairingFailure.UNKNOWN))
        assertEquals(R.string.watchpair_error_restart, pairErrorRes(AdbException.RestartRequired()))
        assertEquals(R.string.watchpair_error_other, pairErrorRes(RuntimeException()))
    }
}
