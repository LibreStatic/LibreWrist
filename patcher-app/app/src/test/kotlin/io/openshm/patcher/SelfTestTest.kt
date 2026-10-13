package io.openshm.patcher

import io.openshm.patcher.adb.ShellResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.data.LoadedCatalogue
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.PhoneProbe
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.home.homeStatusOf
import io.openshm.patcher.ui.screens.selftest.BridgeStatus
import io.openshm.patcher.ui.screens.selftest.BridgeStatusSource
import io.openshm.patcher.ui.screens.selftest.FileHasher
import io.openshm.patcher.ui.screens.selftest.InstalledPackage
import io.openshm.patcher.ui.screens.selftest.PackageInspector
import io.openshm.patcher.ui.screens.selftest.ReportHeader
import io.openshm.patcher.ui.screens.selftest.SelfTestFix
import io.openshm.patcher.ui.screens.selftest.SelfTestParsers
import io.openshm.patcher.ui.screens.selftest.SelfTestResult
import io.openshm.patcher.ui.screens.selftest.SelfTestRow
import io.openshm.patcher.ui.screens.selftest.SelfTestRunner
import io.openshm.patcher.ui.screens.selftest.SelfTestUiState
import io.openshm.patcher.ui.screens.selftest.WatchShell
import io.openshm.patcher.ui.screens.selftest.answer
import io.openshm.patcher.ui.screens.selftest.buildSelfTestReport
import io.openshm.patcher.ui.screens.selftest.engineLogSummary
import io.openshm.patcher.ui.screens.selftest.result
import io.openshm.patcher.adb.AdbException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val PREFIX = "io.test"
private const val CERT = "aa11bb22cc33dd44ee55ff6600770088aa11bb22cc33dd44ee55ff6600770088"
private const val OTHER_CERT = "00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff"
private val PHONE_OUTPUTS = listOf("shm-phone", "bridge-phone")
private val WATCH_OUTPUTS = listOf("shm-watch", "bridge-watch", "alarm-fork")
private const val STOCK = "com.samsung.android.shealthmonitor"

/** Every interface of the runner as a configurable fake; the defaults describe a fully healthy setup. */
private class World(val catalogue: LoadedCatalogue) {
    fun pkg(id: String) = catalogue.output(id).packageName(PREFIX)
    fun sha(id: String) = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }

    var cert: String? = CERT
    var build: BuiltBuild? = BuiltBuild(
        "k", PREFIX, catalogue.serial, 0L,
        (PHONE_OUTPUTS + WATCH_OUTPUTS).map { BuiltRecord(it, "/files/$it.apk", 1L, sha(it)) },
    )
    val installed = (PHONE_OUTPUTS).associate { pkg(it) to InstalledPackage("1.0", setOf(CERT), "/phone/$it.apk") }.toMutableMap<String, InstalledPackage?>()
    val phoneFileSha = PHONE_OUTPUTS.associate { "/phone/$it.apk" to sha(it) }.toMutableMap<String, String?>()
    var bridgeStatus: BridgeStatus = BridgeStatus.Reading(serviceRunning = true, linkUp = true, hello = true)
    val queriedAuthorities = ArrayList<String>()
    var watchConnected = true
    val watchCommands = ArrayList<String>()
    var watchFailure: IOException? = null

    val listenerOn = mutableSetOf(pkg("bridge-phone"))
    val deniedPermissions = mutableSetOf<String>()
    val missingWatchPermissions = mutableSetOf<String>()
    var watchInstalled = (WATCH_OUTPUTS).associate { pkg(it) to "/data/app/~~x==/${pkg(it)}-1==/base.apk" }.toMutableMap()
    val watchFileSha = WATCH_OUTPUTS.associate { "/data/app/~~x==/${pkg(it)}-1==/base.apk" to sha(it) }.toMutableMap()
    var stockDisabled = true
    var stockInstalled = true

    val leftoverWatch = mutableListOf<String>()
    val leftoverPhone = mutableListOf<String>()

    val inspector = object : PackageInspector {
        override fun inspect(pkg: String) = installed[pkg]
        override fun listenerPackages() = listenOf() + leftoverPhone
    }
    val hasher = object : FileHasher {
        override fun sha256(path: String) = phoneFileSha[path]
    }
    val bridge = object : BridgeStatusSource {
        override fun query(authority: String): BridgeStatus {
            queriedAuthorities += authority
            return bridgeStatus
        }
    }
    val probe = object : PhoneProbe {
        override fun permissionGranted(pkg: String, permission: String) = permission !in deniedPermissions
        override fun appOpAllowed(pkg: String, op: String): Boolean? = true
        override fun listenerEnabled(pkg: String, component: String) = pkg in listenerOn
    }
    val watch = object : WatchShell {
        override suspend fun connect() = watchConnected
        override suspend fun run(command: String): ShellResult {
            watchCommands += command
            watchFailure?.let { throw it }
            return answer(command)
        }
    }

    private fun ok(out: String) = ShellResult(0, out, "")
    private fun fail() = ShellResult(1, "", "not found")

    private fun answer(command: String): ShellResult {
        val words = command.split(' ')
        return when {
            command == "pm list packages" -> ok((watchInstalled.keys + leftoverWatch + STOCK).joinToString("\n") { "package:$it" })
            command == "pm list packages -d" -> ok(if (stockDisabled) "package:$STOCK\n" else "")
            words[0] == "pm" && words[1] == "path" ->
                if (words[2] == STOCK) { if (stockInstalled) ok("package:/system/app/Stock/Stock.apk\n") else fail() }
                else watchInstalled[words[2]]?.let { ok("package:$it\n") } ?: fail()
            words[0] == "sha256sum" -> watchFileSha[words[1]]?.let { ok("$it  ${words[1]}\n") } ?: fail()
            words[0] == "dumpsys" && words[1] == "package" -> ok(
                catalogue.grants.values.flatten().filter { it.engine?.kind == "permission" }.joinToString("\n") {
                    val name = it.engine!!.fields.getValue("name")
                    "      $name: granted=${name !in missingWatchPermissions}, flags=[ ]"
                },
            )
            words[0] == "dumpsys" && words[1] == "deviceidle" -> ok(watchInstalled.keys.joinToString("\n") { "user,$it,10100" })
            words[0] == "appops" -> ok("${words[3]}: allow\n")
            command == "settings get secure enabled_notification_listeners" ->
                ok(watchInstalled.keys.joinToString(":") { "$it/$it.SamsungAlarmListener" })
            command.startsWith("settings get global ") ->
                ok(
                    catalogue.grants.values.flatten().firstOrNull { it.engine?.kind == "globalSetting" }
                        ?.engine?.fields?.get("value")?.plus("\n") ?: "null\n",
                )
            words[0] == "settings" -> ok("0\n")
            else -> fail()
        }
    }

    private fun listenOf() = listenerOn.toList()

    fun runner() = SelfTestRunner(
        catalogue = catalogue,
        outputIds = PHONE_OUTPUTS + WATCH_OUTPUTS,
        prefix = PREFIX,
        build = { build },
        signingCertSha256 = { cert },
        packages = inspector,
        hasher = hasher,
        phoneGrants = PhoneGrants(probe),
        bridge = bridge,
        watch = watch,
        io = Dispatchers.Unconfined,
    )
}

private fun SelfTestUiState.rows() = sections.flatMap { it.rows }
private fun SelfTestUiState.row(id: String): SelfTestRow = rows().first { it.id == id }

class SelfTestRunnerTest {
    private lateinit var world: World

    @Before
    fun setUp() {
        world = World(TestCatalogue.load())
    }

    private fun run(): SelfTestUiState {
        val runner = world.runner()
        val state = MutableStateFlow(runner.initial())
        runBlocking { runner.run(state) }
        return state.value
    }

    private fun automated(state: SelfTestUiState) = state.rows().filter { !it.isHuman }

    @Test
    fun `leftover bridge rows pass when no copy of an earlier prefix is installed`() {
        val state = run()
        assertEquals(CheckStatus.Passed, state.row("watch-leftovers").status)
        assertEquals(CheckStatus.Passed, state.row("phone-leftovers").status)
    }

    @Test
    fun `leftover bridge rows fail and list the packages of an earlier prefix`() {
        world.leftoverWatch += "io.openshm.u98ff48.shmbridge.watch"
        world.leftoverPhone += "ar.facu.shmbridge.phone"
        val state = run()
        val watchRow = state.row("watch-leftovers")
        val phoneRow = state.row("phone-leftovers")
        assertEquals(CheckStatus.Failed, watchRow.status)
        assertEquals(listOf("io.openshm.u98ff48.shmbridge.watch"), watchRow.detail?.args)
        assertEquals(SelfTestFix.InstallWatch, watchRow.fix)
        assertEquals(CheckStatus.Failed, phoneRow.status)
        assertEquals(listOf("ar.facu.shmbridge.phone"), phoneRow.detail?.args)
        assertEquals(SelfTestFix.InstallPhone, phoneRow.fix)
    }

    @Test
    fun `initial state lists every row waiting, questions included`() {
        val state = world.runner().initial()
        assertTrue(state.rows().all { it.status == CheckStatus.Pending })
        assertTrue(state.running)
        assertEquals(4, state.rows().count { it.isHuman })
        assertEquals("phone-pkg-shm-phone", state.rows().first().id)
        assertEquals(
            listOf("phone-pkg-shm-phone", "phone-pkg-bridge-phone"),
            state.rows().filter { it.id.startsWith("phone-pkg-") }.map { it.id },
        )
    }

    @Test
    fun `a healthy setup passes every automated row and leaves the questions open`() {
        val state = run()
        val failing = automated(state).filter { it.status != CheckStatus.Passed }
        assertTrue("not passed: ${failing.map { it.id to it.status }}", failing.isEmpty())
        assertFalse(state.running)
        assertFalse(state.finished)
        assertNull(state.result(1L))
        assertEquals(1, state.humanCheck?.index)
        assertEquals(4, state.humanCheck?.total)
        assertEquals(listOf("io.test.shmbridge.phone.status"), world.queriedAuthorities)
    }

    @Test
    fun `answering every question yes ends in an all passed result`() {
        var state = run()
        repeat(4) { state = state.answer(true) }
        assertTrue(state.allPassed)
        assertTrue(state.canContinue)
        assertNull(state.humanCheck)
        val result = state.result(5L)!!
        assertEquals(SelfTestResult(5L, state.totalCount, 0, 0), result)
        assertEquals(CheckStatus.Passed, result.overall)
    }

    @Test
    fun `answering no fails that question with its fix and blocks continuing`() {
        var state = run().answer(false)
        val row = state.row("human-platform")
        assertEquals(CheckStatus.Failed, row.status)
        assertEquals(SelfTestFix.DevMode, row.fix)
        repeat(3) { state = state.answer(true) }
        assertTrue(state.finished)
        assertFalse(state.canContinue)
        assertEquals(CheckStatus.Failed, state.result(1L)!!.overall)
    }

    @Test
    fun `installed file with another hash than the build fails with a reinstall fix`() {
        world.phoneFileSha["/phone/shm-phone.apk"] = "f".repeat(64)
        val row = run().row("phone-pkg-shm-phone")
        assertEquals(CheckStatus.Failed, row.status)
        assertEquals(R.string.selftest_detail_hash_mismatch, row.detail?.res)
        assertEquals(SelfTestFix.InstallPhone, row.fix)
    }

    @Test
    fun `a package signed with another certificate fails`() {
        world.installed[world.pkg("bridge-phone")] = InstalledPackage("1.0", setOf(OTHER_CERT), "/phone/bridge-phone.apk")
        val row = run().row("phone-pkg-bridge-phone")
        assertEquals(CheckStatus.Failed, row.status)
        assertEquals(R.string.selftest_detail_wrong_cert, row.detail?.res)
    }

    @Test
    fun `certificate comparison ignores case`() {
        world.installed[world.pkg("bridge-phone")] = InstalledPackage("1.0", setOf(CERT.uppercase()), "/phone/bridge-phone.apk")
        assertEquals(CheckStatus.Passed, run().row("phone-pkg-bridge-phone").status)
    }

    @Test
    fun `a missing package fails and its access row is skipped`() {
        world.installed.remove(world.pkg("bridge-phone"))
        val state = run()
        assertEquals(CheckStatus.Failed, state.row("phone-pkg-bridge-phone").status)
        assertEquals(R.string.selftest_detail_not_installed, state.row("phone-pkg-bridge-phone").detail?.res)
        assertEquals(CheckStatus.Warning, state.row("phone-grants-bridge-phone").status)
        assertEquals(R.string.selftest_detail_skipped_not_installed, state.row("phone-grants-bridge-phone").detail?.res)
    }

    @Test
    fun `no recorded build for another prefix is a warning that points to the build`() {
        world.build = world.build!!.copy(prefix = "io.other")
        val row = run().row("phone-pkg-shm-phone")
        assertEquals(CheckStatus.Warning, row.status)
        assertEquals(SelfTestFix.Build, row.fix)
    }

    @Test
    fun `an unreadable signing key is a warning, not a failure`() {
        world.cert = null
        val row = run().row("phone-pkg-shm-phone")
        assertEquals(CheckStatus.Warning, row.status)
        assertEquals(R.string.selftest_detail_cert_unavailable, row.detail?.res)
    }

    @Test
    fun `phone access missing names what is missing`() {
        world.listenerOn.clear()
        world.deniedPermissions += "android.permission.BLUETOOTH_CONNECT"
        val row = run().row("phone-grants-bridge-phone")
        assertEquals(CheckStatus.Failed, row.status)
        assertEquals(R.string.selftest_detail_grants_missing, row.detail?.res)
        val missing = row.detail!!.args.single()
        assertTrue(missing, "BLUETOOTH_CONNECT" in missing && "BridgeNotificationListener" in missing)
        assertEquals(SelfTestFix.InstallPhone, row.fix)
    }

    @Test
    fun `bridge status provider denied fails with the build as the fix`() {
        world.bridgeStatus = BridgeStatus.Denied
        val row = run().row("link-bridge")
        assertEquals(CheckStatus.Failed, row.status)
        assertEquals(R.string.selftest_detail_bridge_denied, row.detail?.res)
        assertEquals(SelfTestFix.Build, row.fix)
    }

    @Test
    fun `bridge status provider missing fails`() {
        world.bridgeStatus = BridgeStatus.ProviderMissing
        val row = run().row("link-bridge")
        assertEquals(CheckStatus.Failed, row.status)
        assertEquals(R.string.selftest_detail_bridge_missing, row.detail?.res)
    }

    @Test
    fun `bridge link states are reported one by one`() {
        val cases = listOf(
            BridgeStatus.Reading(serviceRunning = false, linkUp = false, hello = false) to (CheckStatus.Failed to R.string.selftest_detail_bridge_not_running),
            BridgeStatus.Reading(serviceRunning = true, linkUp = false, hello = false) to (CheckStatus.Failed to R.string.selftest_detail_bridge_link_down),
            BridgeStatus.Reading(serviceRunning = true, linkUp = true, hello = false) to (CheckStatus.Warning to R.string.selftest_detail_bridge_no_hello),
            BridgeStatus.Reading(serviceRunning = true, linkUp = true, hello = true) to (CheckStatus.Passed to R.string.selftest_detail_bridge_ok),
        )
        for ((status, expected) in cases) {
            world.bridgeStatus = status
            val row = run().row("link-bridge")
            assertEquals(expected.first, row.status)
            assertEquals(expected.second, row.detail?.res)
        }
    }

    @Test
    fun `watch not connected skips every watch row with a pairing fix and runs no command`() {
        world.watchConnected = false
        val state = run()
        val watchRows = state.sections[1].rows
        assertTrue(watchRows.isNotEmpty())
        for (row in watchRows) {
            assertEquals(row.id, CheckStatus.Warning, row.status)
            assertEquals(R.string.selftest_detail_watch_skipped, row.detail?.res)
            assertEquals(SelfTestFix.WatchPair, row.fix)
        }
        assertTrue(world.watchCommands.isEmpty())
        // The phone side is unaffected.
        assertEquals(CheckStatus.Passed, state.row("phone-pkg-shm-phone").status)
        assertEquals(CheckStatus.Passed, state.row("link-bridge").status)
    }

    @Test
    fun `a broken watch link during a check is a warning with a pairing fix`() {
        world.watchFailure = IOException("connection reset")
        val row = run().row("watch-pkg-shm-watch")
        assertEquals(CheckStatus.Warning, row.status)
        assertEquals(R.string.selftest_detail_watch_no_answer, row.detail?.res)
        assertEquals(SelfTestFix.WatchPair, row.fix)
    }

    @Test
    fun `a watch command that answers with a protocol error is not reported as an unreachable watch`() {
        world.watchFailure = AdbException.Protocol("unexpected shell packet")
        val row = run().row("watch-grants-bridge-watch")
        assertEquals(CheckStatus.Warning, row.status)
        assertEquals(R.string.selftest_detail_error, row.detail?.res)
        assertEquals(SelfTestFix.WatchGrants, row.fix)
    }

    @Test
    fun `watch package with another hash than the build fails`() {
        val path = world.watchInstalled.getValue(world.pkg("bridge-watch"))
        world.watchFileSha[path] = "d".repeat(64)
        val row = run().row("watch-pkg-bridge-watch")
        assertEquals(CheckStatus.Failed, row.status)
        assertEquals(R.string.selftest_detail_hash_mismatch, row.detail?.res)
        assertEquals(SelfTestFix.InstallWatch, row.fix)
        assertTrue(world.watchCommands.contains("sha256sum $path"))
    }

    @Test
    fun `watch package not installed fails and skips its access row`() {
        world.watchInstalled.remove(world.pkg("alarm-fork"))
        val state = run()
        assertEquals(CheckStatus.Failed, state.row("watch-pkg-alarm-fork").status)
        assertEquals(R.string.selftest_detail_skipped_not_installed, state.row("watch-grants-alarm-fork").detail?.res)
    }

    @Test
    fun `watch access read back from the device names a missing permission`() {
        world.missingWatchPermissions += "android.permission.BLUETOOTH_CONNECT"
        val row = run().row("watch-grants-bridge-watch")
        assertEquals(CheckStatus.Failed, row.status)
        assertTrue("BLUETOOTH_CONNECT" in row.detail!!.args.single())
        assertEquals(SelfTestFix.WatchGrants, row.fix)
    }

    @Test
    fun `an optional grant that is missing is only a warning`() {
        world.missingWatchPermissions += "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        val row = run().row("watch-grants-shm-watch")
        assertEquals(CheckStatus.Warning, row.status)
        assertEquals(R.string.selftest_detail_grants_optional, row.detail?.res)
        assertTrue(run().canContinueAfterAnswers())
    }

    @Test
    fun `the stock app still on is a warning because turning it off is optional`() {
        world.stockDisabled = false
        val row = run().row("watch-stock-$STOCK")
        assertEquals(CheckStatus.Warning, row.status)
        assertEquals(SelfTestFix.DisableStock, row.fix)
    }

    @Test
    fun `a stock app that is not on the watch passes`() {
        world.stockDisabled = false
        world.stockInstalled = false
        assertEquals(CheckStatus.Passed, run().row("watch-stock-$STOCK").status)
    }

    private fun SelfTestUiState.canContinueAfterAnswers(): Boolean {
        var s = this
        repeat(4) { s = s.answer(true) }
        return s.canContinue
    }
}

class SelfTestSupportTest {
    @Test
    fun `sha256sum output is parsed to lowercase hex`() {
        val digest = "ABCDEF0123456789".repeat(4)
        assertEquals(digest.lowercase(), SelfTestParsers.sha256sum("$digest  /data/app/x/base.apk\n"))
        assertEquals(digest.lowercase(), SelfTestParsers.sha256sum("$digest\n"))
        assertNull(SelfTestParsers.sha256sum("sha256sum: /nope: No such file or directory"))
        assertNull(SelfTestParsers.sha256sum("abc  /x"))
    }

    @Test
    fun `only plain absolute paths are used in shell commands`() {
        assertTrue(SelfTestParsers.isSafePath("/data/app/~~aB3-x==/io.test.app-Zy_9==/base.apk"))
        assertFalse(SelfTestParsers.isSafePath("/data/app/x; reboot"))
        assertFalse(SelfTestParsers.isSafePath("/data/app/$(id)/base.apk"))
        assertFalse(SelfTestParsers.isSafePath("relative/base.apk"))
        assertFalse(SelfTestParsers.isSafePath("/data/../etc/x"))
    }

    @Test
    fun `home status reads the persisted result`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(CheckStatus.Pending, homeStatusOf(null, 1L).status)
        assertNull(homeStatusOf(null, 1L).daysAgo)
        val passed = homeStatusOf(SelfTestResult(10 * day, 8, 0, 0), 12 * day + 5)
        assertEquals(CheckStatus.Passed, passed.status)
        assertEquals(2, passed.daysAgo)
        assertEquals(CheckStatus.Warning, homeStatusOf(SelfTestResult(0, 7, 1, 0), 0).status)
        assertEquals(CheckStatus.Failed, homeStatusOf(SelfTestResult(0, 6, 1, 1), 0).status)
        assertEquals(0, homeStatusOf(SelfTestResult(5 * day, 1, 0, 0), 4 * day).daysAgo) // clock went back
    }

    @Test
    fun `the report has versions, ids, results and the log summary but nothing from the devices or keys`() {
        val world = World(TestCatalogue.load())
        val runner = world.runner()
        val state = MutableStateFlow(runner.initial())
        world.phoneFileSha["/phone/shm-phone.apk"] = "f".repeat(64)
        runBlocking { runner.run(state) }
        val header = ReportHeader(
            appVersion = "0.1.0 (1)",
            catalogueSerial = 7,
            inputs = listOf("shm-watch Samsung Health Monitor 1.5.1.022"),
            packages = listOf("shm-phone io.test.shm"),
        )
        val report = buildSelfTestReport(
            header, state.value,
            sectionTitle = { "section$it" },
            rowText = { text -> "text${text.res}(${text.args.joinToString()})" },
            engineLog = engineLogSummary(listOf("== shm-phone (1/5) ==", "start unpack", "FAILED: boom", "x".repeat(500))),
        )
        assertTrue(report.contains("App: 0.1.0 (1)"))
        assertTrue(report.contains("Catalogue serial: 7"))
        assertTrue(report.contains("shm-watch Samsung Health Monitor 1.5.1.022"))
        assertTrue(report.contains("[FAIL]"))
        assertTrue(report.contains("[PASS]"))
        assertTrue(report.contains("== shm-phone (1/5) =="))
        assertTrue(report.contains("FAILED: boom"))
        assertFalse(report.contains("start unpack"))
        assertFalse(report.contains(CERT))
        assertFalse(report.contains("/phone/"))
        assertFalse(report.lines().any { it.length > 400 })
    }

    @Test
    fun `an empty engine log is reported as not available`() {
        val report = buildSelfTestReport(
            ReportHeader("v", 1, emptyList(), emptyList()), SelfTestUiState(), { "" }, { "" }, engineLogSummary(emptyList()),
        )
        assertTrue(report.contains("not available"))
    }
}
