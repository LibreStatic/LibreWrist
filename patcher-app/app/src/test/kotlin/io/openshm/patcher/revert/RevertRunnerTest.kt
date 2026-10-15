package io.openshm.patcher.revert

import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.data.Device
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.ui.screens.revert.Presence
import io.openshm.patcher.ui.screens.revert.RevertAction
import io.openshm.patcher.ui.screens.revert.RevertItem
import io.openshm.patcher.ui.screens.revert.RevertReport
import io.openshm.patcher.ui.screens.revert.RevertRunner
import io.openshm.patcher.ui.screens.revert.RevertScope
import io.openshm.patcher.ui.screens.revert.SkipReason
import io.openshm.patcher.ui.screens.revert.StepOutcome
import io.openshm.patcher.ui.screens.revert.WatchReach
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.openshm.patcher.TestCatalogue

class RevertRunnerTest {
    @Before fun catalogue() = TestCatalogue.install()

    private fun RevertRunner.probeAndRun(skipWatch: Boolean = false): Pair<io.openshm.patcher.ui.screens.revert.RevertProbe, RevertReport> = runBlocking {
        val probe = probe(includeWatch = !skipWatch)
        probe to run(probe.items, probe.watch == WatchReach.Reached || probe.watch == WatchReach.NotNeeded) { _, _ -> }
    }

    private fun RevertReport.outcomeOf(item: RevertItem) = outcomes.getValue(item.key)

    @Test
    fun aFullInstallIsRevertedAndReportedComplete() {
        val (phone, watch) = fullInstall()
        val (_, report) = runner(phone, watch).probeAndRun()
        assertTrue(report.complete)
        assertEquals(
            setOf("uninstall:${outputPkg("bridge-watch")}", "uninstall:${outputPkg("alarm-fork")}", "uninstall:${outputPkg("shm-watch")}", "enable:$STOCK_HM", "put:doze_always_on=1"),
            watch.calls.toSet(),
        )
        assertEquals(PHONE_APPS, phone.calls.size)
        assertTrue(phone.kinds.isEmpty())
    }

    @Test
    fun theBridgeIsDisassociatedBeforeItIsUninstalled() {
        val (phone, watch) = fullInstall()
        val bridge = outputPkg("bridge-watch")
        watch.associations += io.openshm.patcher.adb.CompanionAssociation(bridge, "B8:34:46:0D:88:F4")
        watch.associations += io.openshm.patcher.adb.CompanionAssociation("other.pkg.x", "B8:34:46:0D:88:F4")
        val (_, report) = runner(phone, watch).probeAndRun()
        assertTrue(report.complete)
        val calls = watch.calls
        assertTrue(calls.indexOf("disassociate:$bridge:B8:34:46:0D:88:F4") in 0 until calls.indexOf("uninstall:$bridge"))
        assertEquals(1, calls.count { it.startsWith("disassociate:") })
        assertEquals(1, watch.associations.size)
    }

    @Test
    fun aFailedDisassociationOrListingStillUninstalls() {
        for (breakIt in listOf<(FakeWatch) -> Unit>({ it.disassociateThrows = IllegalStateException("no") }, { it.listThrows = IllegalStateException("no") })) {
            val (phone, watch) = fullInstall()
            val bridge = outputPkg("bridge-watch")
            watch.associations += io.openshm.patcher.adb.CompanionAssociation(bridge, "B8:34:46:0D:88:F4")
            breakIt(watch)
            val (_, report) = runner(phone, watch).probeAndRun()
            assertTrue(report.complete)
            assertTrue("uninstall:$bridge" in watch.calls)
        }
    }

    @Test
    fun missingPackagesAreSkippedAndReportedAbsent() {
        val phone = FakePhone()
        val watch = FakeWatch()
        val (probe, report) = runner(phone, watch).probeAndRun()
        assertTrue(probe.items.filter { it.action == RevertAction.Uninstall }.all { it.presence == Presence.Absent })
        assertTrue(phone.calls.isEmpty() && watch.calls.isEmpty())
        assertTrue(report.complete)
    }

    @Test
    fun systemPackagesAreNeverUninstalledAndDoNotMakeTheRunPartial() {
        val (phone, watch) = fullInstall()
        phone.kinds[outputPkg("shm-phone")] = PackageKind.System
        watch.kinds[outputPkg("shm-watch")] = PackageKind.System
        val (probe, report) = runner(phone, watch).probeAndRun()
        assertTrue(probe.items.any { it.presence == Presence.System })
        assertFalse(phone.calls.contains("uninstall:${outputPkg("shm-phone")}"))
        assertFalse(watch.calls.contains("uninstall:${outputPkg("shm-watch")}"))
        assertTrue(report.complete)
    }

    @Test
    fun theWatchIsKeptAwakeOnlyWhenItIsCovered() {
        val (phone, watch) = fullInstall()
        val r = runner(phone, watch)
        val items = runBlocking { r.probe().items }
        runBlocking { r.run(items, true) { _, _ -> } }
        assertEquals(1, watch.stayAwakeCalls)
        runBlocking { r.run(items, false) { _, _ -> } }
        assertEquals(1, watch.stayAwakeCalls)
    }

    @Test
    fun aPackageThatTurnedSystemAfterTheProbeIsStillNotTouched() {
        val (phone, watch) = fullInstall()
        val r = runner(phone, watch)
        val items = runBlocking { r.probe().items }
        phone.kinds[outputPkg("bridge-phone")] = PackageKind.System
        val report = runBlocking { r.run(items, true) { _, _ -> } }
        assertFalse(phone.calls.contains("uninstall:${outputPkg("bridge-phone")}"))
        assertEquals(StepOutcome.Skipped(SkipReason.SystemApp), report.outcomes["${Device.Phone}:${RevertAction.Uninstall}:${outputPkg("bridge-phone")}"])
    }

    @Test
    fun withSamsungHealthOmittedOnlyHealthMonitorAndTheBridgeAreRemovedOnThePhone() {
        val (phone, watch) = fullInstall()
        SHEALTH?.let { phone.kinds.remove(it) }
        val (_, report) = runner(phone, watch).probeAndRun()
        assertEquals(setOf("uninstall:${outputPkg("bridge-phone")}", "uninstall:${outputPkg("shm-phone")}"), phone.calls.toSet())
        assertTrue(report.complete)
    }

    @Test
    fun aProfileWithoutTheAlarmForkWorks() {
        val (phone, watch) = fullInstall()
        watch.kinds.remove(outputPkg("alarm-fork"))
        val (_, report) = runner(phone, watch).probeAndRun()
        assertFalse(watch.calls.contains("uninstall:${outputPkg("alarm-fork")}"))
        assertTrue(report.complete)
    }

    @Test
    fun theStockAppIsEnabledOnlyWhenItIsDisabled() {
        val (phone, watch) = fullInstall()
        watch.disabled.clear()
        runner(phone, watch).probeAndRun()
        assertFalse(watch.calls.any { it.startsWith("enable:") })
    }

    @Test
    fun alwaysOnIsRestoredOnlyWhenItReadsZero() {
        for (value in listOf("1", null)) {
            val (phone, watch) = fullInstall()
            if (value == null) watch.settings.remove("doze_always_on") else watch.settings["doze_always_on"] = value
            runner(phone, watch).probeAndRun()
            assertFalse("value $value", watch.calls.any { it.startsWith("put:") })
        }
        val (phone, watch) = fullInstall()
        runner(phone, watch).probeAndRun()
        assertEquals("1", watch.settings["doze_always_on"])
    }

    private val key = "hidden_api_blacklist_exemptions"
    private val ours = "Lcom/samsung/android/hardware/sensormanager/"

    private fun exemptionItem(probe: io.openshm.patcher.ui.screens.revert.RevertProbe) =
        probe.items.single { it.action == RevertAction.RemoveExemption }

    @Test
    fun theExemptionIsPresentOnlyWhenOurExactEntryIsListed() {
        val cases = mapOf<String?, Presence>(
            ours to Presence.Present,
            "Lother/,$ours,Lmore/" to Presence.Present,
            null to Presence.Absent,
            "Lother/" to Presence.Absent,
            "${ours}extra/" to Presence.Absent,
        )
        for ((value, expected) in cases) {
            val (phone, watch) = fullInstall()
            if (value != null) watch.globals[key] = value
            val (probe, _) = runner(phone, watch).probeAndRun()
            assertEquals("value $value", expected, exemptionItem(probe).presence)
        }
    }

    @Test
    fun ourEntryIsRemovedAndForeignOnesKept() {
        val (phone, watch) = fullInstall()
        watch.globals[key] = "Lother/,$ours,${ours}extra/"
        val (probe, report) = runner(phone, watch).probeAndRun()
        assertEquals(StepOutcome.Done, report.outcomeOf(exemptionItem(probe)))
        assertEquals("Lother/,${ours}extra/", watch.globals[key])
        assertTrue(watch.calls.contains("putGlobal:$key=Lother/,${ours}extra/"))
        assertTrue(report.complete)
    }

    @Test
    fun theSettingIsDeletedWhenOursWasTheOnlyEntry() {
        val (phone, watch) = fullInstall()
        watch.globals[key] = ours
        val (probe, report) = runner(phone, watch).probeAndRun()
        assertEquals(StepOutcome.Done, report.outcomeOf(exemptionItem(probe)))
        assertFalse(watch.globals.containsKey(key))
        assertTrue(watch.calls.contains("deleteGlobal:$key"))
        assertFalse(watch.calls.any { it.startsWith("putGlobal:") })
    }

    @Test
    fun theExemptionRunsAfterTheWatchUninstalls() {
        val (phone, watch) = fullInstall()
        watch.globals[key] = ours
        runner(phone, watch).probeAndRun()
        val at = watch.calls.indexOf("deleteGlobal:$key")
        assertTrue(at > watch.calls.indexOfLast { it.startsWith("uninstall:") })
    }

    @Test
    fun anEntryThatVanishedAfterTheProbeIsSkipped() {
        val (phone, watch) = fullInstall()
        watch.globals[key] = ours
        val runner = runner(phone, watch)
        val report = runBlocking {
            val probe = runner.probe()
            watch.globals.remove(key)
            runner.run(probe.items, true) { _, _ -> }
        }
        assertEquals(StepOutcome.Skipped(SkipReason.NothingToDo), report.outcomes["Watch:RemoveExemption:$key"])
        assertFalse(watch.calls.any { it.contains("Global:") })
    }

    @Test
    fun aListThatCannotBeRewrittenSafelyIsFailedAndLeftAlone() {
        val (phone, watch) = fullInstall()
        val original = "L'quoted/,$ours"
        watch.globals[key] = original
        val (probe, report) = runner(phone, watch).probeAndRun()
        assertTrue(report.outcomeOf(exemptionItem(probe)) is StepOutcome.Failed)
        assertEquals(original, watch.globals[key])
        assertFalse(watch.calls.any { it.contains("Global:") })
        assertFalse(report.complete)
    }

    @Test
    fun anUnreachableWatchStillLetsThePhoneRevertAndTheRunIsPartial() {
        val (phone, watch) = fullInstall()
        watch.down = AdbException.PairingRequired()
        val (probe, report) = runner(phone, watch).probeAndRun()
        assertTrue(probe.watch is WatchReach.Unreachable)
        assertTrue(probe.items.none { it.device == Device.Watch })
        assertEquals(PHONE_APPS, phone.calls.size)
        assertFalse(report.complete)
    }

    @Test
    fun aSkippedWatchIsNeverContactedAndTheRunIsPartial() {
        val (phone, watch) = fullInstall()
        watch.down = IOException("must not be asked")
        val (probe, report) = runner(phone, watch).probeAndRun(skipWatch = true)
        assertEquals(WatchReach.Skipped, probe.watch)
        assertEquals(PHONE_APPS, phone.calls.size)
        assertFalse(report.complete)
    }

    @Test
    fun theWatchDroppingMidRunMarksTheRestAndTheRunContinuesOnThePhone() {
        val (phone, watch) = fullInstall()
        watch.dropAfterUninstalls = 1
        val (_, report) = runner(phone, watch).probeAndRun()
        assertEquals(1, watch.calls.count { it.startsWith("uninstall:") })
        assertEquals(4, report.outcomes.values.count { it is StepOutcome.WatchUnreachable })
        assertEquals(PHONE_APPS, phone.calls.size)
        assertFalse(report.complete)
    }

    @Test
    fun aDeclinedPhonePromptIsACancelledOutcomeAndTheRunGoesOn() {
        val (phone, watch) = fullInstall()
        phone.results[outputPkg("bridge-phone")] = UninstallResult.Failure("STATUS_FAILURE_ABORTED")
        val (_, report) = runner(phone, watch).probeAndRun()
        assertEquals(StepOutcome.Cancelled, report.outcomes.values.single { it == StepOutcome.Cancelled })
        assertEquals(PHONE_APPS, phone.calls.size)
        assertFalse(report.complete)
    }

    @Test
    fun anUninstallFailureAndAnExceptionAreRecordedPerStepWithoutAbortingTheOthers() {
        val (phone, watch) = fullInstall()
        watch.failing[outputPkg("bridge-watch")] = UninstallResult.Failure("DELETE_FAILED_INTERNAL_ERROR")
        watch.throwing[outputPkg("alarm-fork")] = IllegalStateException("boom")
        phone.throwing[SHEALTH ?: outputPkg("bridge-phone")] = SecurityException("denied")
        val (_, report) = runner(phone, watch).probeAndRun()
        assertEquals(3, report.outcomes.values.count { it is StepOutcome.Failed })
        assertTrue(watch.calls.contains("enable:$STOCK_HM"))
        assertTrue(phone.calls.contains("uninstall:${outputPkg("shm-phone")}"))
        assertFalse(report.complete)
    }

    @Test
    fun anInstallUnderAnEarlierPrefixIsFoundAndRemoved() {
        val old = "io.openshm.uabcdef"
        val phone = FakePhone(kinds = mutableMapOf(outputPkg("bridge-phone", old) to PackageKind.User), listeners = listOf(outputPkg("bridge-phone", old)))
        val watch = FakeWatch(kinds = mutableMapOf(outputPkg("bridge-watch", old) to PackageKind.User))
        val (_, report) = runner(phone, watch).probeAndRun()
        assertEquals(listOf("uninstall:${outputPkg("bridge-phone", old)}"), phone.calls)
        assertEquals(listOf("uninstall:${outputPkg("bridge-watch", old)}"), watch.calls)
        assertTrue(report.complete)
    }

    @Test
    fun thePatchersOwnPackageIsNeverRemoved() {
        val (phone, watch) = fullInstall()
        phone.kinds[OWN] = PackageKind.User
        runner(phone, watch).probeAndRun()
        assertFalse(phone.calls.contains("uninstall:$OWN"))
    }

    @Test
    fun theWatchScopeNeverAsksThePhoneAndKeepsTheWatchExtras() {
        val (phone, watch) = fullInstall()
        watch.globals[key] = ours
        val (probe, report) = runner(phone, watch, scope = RevertScope.Watch).probeAndRun()
        assertEquals(WatchReach.Reached, probe.watch)
        assertTrue(probe.items.none { it.device == Device.Phone })
        assertTrue(phone.calls.isEmpty() && phone.reads.isEmpty())
        assertEquals(
            setOf("uninstall:${outputPkg("bridge-watch")}", "uninstall:${outputPkg("alarm-fork")}", "uninstall:${outputPkg("shm-watch")}", "enable:$STOCK_HM", "put:doze_always_on=1", "deleteGlobal:$key"),
            watch.calls.toSet(),
        )
        assertTrue(report.complete)
    }

    @Test
    fun aPhoneAppScopeNeverContactsTheWatchAndIsComplete() {
        val (phone, watch) = fullInstall()
        watch.down = IOException("must not be asked")
        val (probe, report) = runner(phone, watch, scope = RevertScope.Output("bridge-phone")).probeAndRun()
        assertEquals(WatchReach.NotNeeded, probe.watch)
        assertEquals(listOf("uninstall:${outputPkg("bridge-phone")}"), phone.calls)
        assertTrue(watch.calls.isEmpty())
        assertTrue(report.complete)
    }

    @Test
    fun theWatchMonitorScopeAlsoTurnsTheStockAppBackOn() {
        val (phone, watch) = fullInstall()
        watch.globals[key] = ours
        val (_, report) = runner(phone, watch, scope = RevertScope.Output("shm-watch")).probeAndRun()
        assertEquals(setOf("uninstall:${outputPkg("shm-watch")}", "enable:$STOCK_HM", "put:doze_always_on=1", "deleteGlobal:$key"), watch.calls.toSet())
        assertTrue(phone.calls.isEmpty())
        assertTrue(report.complete)
    }

    @Test
    fun anotherWatchAppScopeLeavesTheStockAppAndSettingsAlone() {
        val (phone, watch) = fullInstall()
        watch.globals[key] = ours
        val (probe, _) = runner(phone, watch, scope = RevertScope.Output("alarm-fork")).probeAndRun()
        assertTrue(probe.items.all { it.action == RevertAction.Uninstall })
        assertEquals(listOf("uninstall:${outputPkg("alarm-fork")}"), watch.calls)
        assertEquals(ours, watch.globals[key])
    }

    @Test
    fun aScopedRunStillFindsAnInstallUnderAnEarlierPrefix() {
        val old = "io.openshm.uabcdef"
        val phone = FakePhone(kinds = mutableMapOf(outputPkg("bridge-phone", old) to PackageKind.User), listeners = listOf(outputPkg("bridge-phone", old)))
        val watch = FakeWatch(kinds = mutableMapOf(outputPkg("bridge-watch", old) to PackageKind.User, outputPkg("alarm-fork", old) to PackageKind.User))
        runner(phone, watch, scope = RevertScope.Output("bridge-phone")).probeAndRun()
        assertEquals(listOf("uninstall:${outputPkg("bridge-phone", old)}"), phone.calls)
        runner(phone, watch, scope = RevertScope.Output("bridge-watch")).probeAndRun()
        assertEquals(listOf("uninstall:${outputPkg("bridge-watch", old)}"), watch.calls)
    }
}
