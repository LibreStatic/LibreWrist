package io.openshm.patcher

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.ui.screens.home.HomeEvent
import io.openshm.patcher.ui.screens.home.HomeUiState
import io.openshm.patcher.ui.screens.home.InstalledBuild
import io.openshm.patcher.ui.screens.home.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeRestoreTest {
    @Before fun catalogue() = TestCatalogue.install()

    private val bridge = InstalledBuild("bridge-watch", "Bridge", "a.b.watch", Device.Watch)
    private val monitor = InstalledBuild("shm-watch", "Monitor", "a.b.shm", Device.Watch)
    private val state = HomeUiState(builds = listOf(bridge, monitor))

    @Test
    fun askingToRestoreOpensTheSheetForThatBuild() {
        val asked = state.reduce(HomeEvent.AskRestore("bridge-watch"))
        assertEquals(bridge, asked.restoreTarget)
        assertTrue(asked.restoreSheetVisible)
    }

    @Test
    fun anUnknownBuildOpensNoSheet() {
        assertNull(state.reduce(HomeEvent.AskRestore("nope")).restoreTarget)
    }

    @Test
    fun confirmingOrDismissingClosesTheSheet() {
        val asked = state.reduce(HomeEvent.AskRestore("shm-watch"))
        assertFalse(asked.reduce(HomeEvent.ConfirmRestore).restoreSheetVisible)
        assertFalse(asked.reduce(HomeEvent.DismissRestore).restoreSheetVisible)
    }

    @Test
    fun restoringTheWatchClosesTheMenu() {
        assertFalse(state.copy(menuOpen = true).reduce(HomeEvent.RestoreWatch).menuOpen)
    }

    @Test
    fun restoredOutputsAreLeftOutOfTheActiveOnes() {
        val session = SessionState(watchProfile = WatchProfile.WearOs5, restoredOutputs = setOf("alarm-fork", "bridge-phone"))
        assertEquals(session.outputIds - setOf("alarm-fork", "bridge-phone"), session.activeOutputIds)
        assertTrue("alarm-fork" in session.outputIds)
        assertEquals(SessionState().outputIds, SessionState().activeOutputIds)
    }
}
