package io.openshm.patcher.revert

import io.openshm.patcher.TestCatalogue
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.screens.revert.Presence
import io.openshm.patcher.ui.screens.revert.RevertAction
import io.openshm.patcher.ui.screens.revert.RevertProbe
import io.openshm.patcher.ui.screens.revert.RevertScope
import io.openshm.patcher.ui.screens.revert.WatchReach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RevertScopeTest {
    @Before fun catalogue() = TestCatalogue.install()

    private fun probe(scope: RevertScope, phone: FakePhone, watch: FakeWatch, stored: String? = PREFIX): RevertProbe =
        runBlocking { runner(phone, watch, stored, scope).probe() }

    private fun RevertProbe.uninstalls() = items.filter { it.action == RevertAction.Uninstall }.map { it.pkg }.toSet()

    @Test
    fun scopesRoundTripThroughTheRouteValue() {
        for (scope in listOf(RevertScope.All, RevertScope.Watch, RevertScope.Output("bridge-phone"))) {
            assertEquals(scope, RevertScope.parse(scope.encoded))
        }
        assertEquals("output:shm-watch", RevertScope.Output("shm-watch").encoded)
    }

    @Test
    fun anUnknownOrMissingValueMeansTheWholeSetup() {
        assertEquals(RevertScope.All, RevertScope.parse(null))
        assertEquals(RevertScope.All, RevertScope.parse(""))
        assertEquals(RevertScope.All, RevertScope.parse("output:"))
        assertEquals(RevertScope.All, RevertScope.parse("something"))
    }

    @Test
    fun theRouteCarriesTheScopeAndTheConfirmation() {
        assertEquals("revert?scope=watch&confirmed=false", Route.revert(RevertScope.Watch))
        assertEquals("revert?scope=output:bridge-watch&confirmed=true", Route.revert(RevertScope.Output("bridge-watch"), confirmed = true))
        assertEquals("revert", Route.Revert.path)
    }

    @Test
    fun theWatchScopeDoesNotProbeThePhoneAndKeepsEveryWatchEntry() {
        val (phone, watch) = fullInstall()
        val probe = probe(RevertScope.Watch, phone, watch)
        assertEquals(WatchReach.Reached, probe.watch)
        assertTrue(probe.items.all { it.device == io.openshm.patcher.data.Device.Watch })
        assertEquals(setOf(outputPkg("bridge-watch"), outputPkg("alarm-fork"), outputPkg("shm-watch")), probe.uninstalls())
        assertEquals(
            setOf(RevertAction.Enable, RevertAction.RestoreSetting, RevertAction.RemoveExemption),
            probe.items.map { it.action }.toSet() - RevertAction.Uninstall,
        )
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun aPhoneOutputNeverTouchesTheWatchAndIsCompleteWithoutIt() {
        val (phone, watch) = fullInstall()
        watch.down = java.io.IOException("off")
        val runner = runner(phone, watch, scope = RevertScope.Output("bridge-phone"))
        val (probe, report) = runBlocking {
            val probe = runner.probe()
            probe to runner.run(probe.items, watchCovered = true) { _, _ -> }
        }
        assertEquals(WatchReach.NotNeeded, probe.watch)
        assertEquals(setOf(outputPkg("bridge-phone")), probe.uninstalls())
        assertEquals(setOf("bridge-phone"), probe.items.mapNotNull { it.outputId }.toSet())
        assertTrue(report.complete)
        assertEquals(listOf("uninstall:${outputPkg("bridge-phone")}"), phone.calls)
        assertTrue(watch.calls.isEmpty())
    }

    @Test
    fun theHealthMonitorOutputBringsTheWatchExtras() {
        val (phone, watch) = fullInstall()
        val probe = probe(RevertScope.Output("shm-watch"), phone, watch)
        assertEquals(setOf(outputPkg("shm-watch")), probe.uninstalls())
        assertEquals(
            setOf(RevertAction.Enable, RevertAction.RestoreSetting, RevertAction.RemoveExemption),
            probe.items.map { it.action }.toSet() - RevertAction.Uninstall,
        )
        assertTrue(probe.items.filter { it.action != RevertAction.Uninstall }.all { it.outputId == null })
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun anotherWatchOutputLeavesTheExtrasAlone() {
        val (phone, watch) = fullInstall()
        val probe = probe(RevertScope.Output("alarm-fork"), phone, watch)
        assertEquals(setOf(outputPkg("alarm-fork")), probe.uninstalls())
        assertEquals(1, probe.items.size)
        assertFalse(probe.items.any { it.action == RevertAction.Enable })
    }

    @Test
    fun anInstallUnderAnEarlierPrefixIsStillFoundInAScopedProbe() {
        val old = "io.openshm.u999999"
        val watch = fullInstall().second.apply { kinds[outputPkg("bridge-watch", old)] = PackageKind.User }
        val probe = probe(RevertScope.Output("bridge-watch"), FakePhone(), watch)
        assertEquals(setOf(outputPkg("bridge-watch"), outputPkg("bridge-watch", old)), probe.uninstalls())
        assertTrue(probe.items.all { it.presence == Presence.Present })
    }
}
