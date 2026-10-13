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
        probe to run(probe.items, probe.watch == WatchReach.Reached) { _, _ -> }
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
}
