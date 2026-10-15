package io.openshm.patcher

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.data.RebuildScope
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.requestedRebuildScope
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.SkippedStep
import io.openshm.patcher.ui.nav.rebuildSkippedRoutes
import io.openshm.patcher.ui.nav.skippedRunAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RebuildScopeTest {
    private val catalogue = TestCatalogue.load()
    private val session = SessionState()
    private val sessionIds = session.outputIds

    @Before fun install() = TestCatalogue.install()

    private fun app(id: String, input: String?, device: Device = Device.Watch) =
        OutputApp(id, id, device, "x.$id", input)

    @Test
    fun requestedScopeOfASharedInputCoversEveryOutputOfThatInput() {
        val outputs = listOf(app("a", "in1"), app("b", "in1"), app("c", "in2"), app("bridge", null))
        assertEquals(setOf("a", "b"), requestedRebuildScope(outputs, "a", listOf("a", "b", "c", "bridge")))
        assertEquals(setOf("a"), requestedRebuildScope(outputs, "a", listOf("a", "c")))
    }

    @Test
    fun requestedScopeOfABridgeIsJustThatOutput() {
        assertEquals(setOf("bridge-watch"), requestedRebuildScope(catalogue.outputs, "bridge-watch", sessionIds))
    }

    @Test
    fun requestedScopeOfASingleAppIsThatApp() {
        assertEquals(setOf("shm-watch"), requestedRebuildScope(catalogue.outputs, "shm-watch", sessionIds))
    }

    @Test
    fun aPhoneOnlyScopeSkipsTheWatchSteps() {
        assertEquals(
            setOf(Route.WatchInstall, Route.DevMode, Route.DisableStock),
            rebuildSkippedRoutes(catalogue.outputs, setOf("shm-phone", "bridge-phone")),
        )
    }

    @Test
    fun aWatchOnlyScopeSkipsThePhoneInstall() {
        assertEquals(setOf(Route.InstallPhone, Route.DevMode, Route.DisableStock), rebuildSkippedRoutes(catalogue.outputs, setOf("alarm-fork")))
    }

    @Test
    fun theWatchMonitorKeepsDeveloperModeAndStockApps() {
        assertEquals(setOf(Route.InstallPhone), rebuildSkippedRoutes(catalogue.outputs, setOf("shm-watch")))
    }

    @Test
    fun noRebuildSkipsNothing() {
        assertTrue(rebuildSkippedRoutes(catalogue.outputs, null).isEmpty())
    }

    @Test
    fun rebuildSkipsAreNeverAnnounced() {
        val announced = listOf(SkippedStep(Route.Profile, 0))
        assertTrue(skippedRunAfter(Route.BuildDone, announced).isEmpty())
    }

    @Test
    fun rebuiltAppsJoinTheRequestedScopeUntilCleared() {
        assertNull(RebuildScope.scope)
        RebuildScope.addRebuilt(listOf("shm-phone"))
        assertNull("nothing to add to outside a rebuild", RebuildScope.state.value)
        RebuildScope.begin(setOf("shm-watch"))
        assertEquals(setOf("shm-watch"), RebuildScope.scope)
        assertTrue(RebuildScope.includes("shm-watch") && !RebuildScope.includes("shm-phone"))
        RebuildScope.addRebuilt(listOf("shm-phone"))
        RebuildScope.addRebuilt(emptyList())
        assertEquals(setOf("shm-watch", "shm-phone"), RebuildScope.scope)
        assertTrue(RebuildScope.includes("shm-phone") && !RebuildScope.includes("alarm-fork"))
        RebuildScope.begin(setOf("alarm-fork"))
        assertEquals("a new rebuild starts from its own request", setOf("alarm-fork"), RebuildScope.scope)
        RebuildScope.clear()
        assertNull(RebuildScope.state.value)
        assertTrue(RebuildScope.includes("anything"))
    }
}
