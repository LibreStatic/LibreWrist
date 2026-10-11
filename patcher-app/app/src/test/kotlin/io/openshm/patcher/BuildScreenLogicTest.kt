package io.openshm.patcher

import io.openshm.patcher.build.BuildRunPhase
import io.openshm.patcher.build.BuildRunState
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.build.BuildStage
import io.openshm.patcher.ui.screens.build.BuildUiState
import io.openshm.patcher.ui.screens.build.CurrentApp
import io.openshm.patcher.ui.screens.build.nextAlmostDoneIndex
import io.openshm.patcher.ui.screens.build.toUiState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildScreenLogicTest {
    private fun app(number: Int, total: Int) = CurrentApp("x", "X", "p.x", Device.Phone, number, total)

    @Test fun almostDoneOnlyForTheLastTwoAppsWhileRunning() {
        fun almost(number: Int, total: Int, running: Boolean = true) = BuildUiState(running = running, currentApp = app(number, total)).almostDone
        assertFalse(almost(1, 5))
        assertFalse(almost(2, 5))
        assertTrue(almost(4, 5))
        assertTrue(almost(5, 5))
        assertTrue(almost(1, 2))
        assertFalse(almost(5, 5, running = false))
        assertFalse(BuildUiState(running = true).almostDone)
    }

    @Test fun nextMessageNeverRepeatsTheCurrentOne() {
        val random = Random(7)
        var current = 0
        repeat(500) {
            val next = nextAlmostDoneIndex(current, 6, random)
            assertTrue(next in 0 until 6)
            assertTrue(next != current)
            current = next
        }
        assertEquals(0, nextAlmostDoneIndex(0, 1, random))
    }

    @Test fun cancelledRunIsNotRunningFailedOrFinished() {
        val run = BuildRunState(phase = BuildRunPhase.Cancelled, stage = BuildStage.Apply, progress = 0.4f)
        val ui = run.toUiState(patchCount = 3, logVisible = false, currentApp = app(2, 5))
        assertTrue(ui.cancelled)
        assertFalse(ui.running)
        assertFalse(ui.failed)
        assertFalse(ui.almostDone)
        assertTrue(ui.stages.none { it.status == CheckStatus.Running })
        assertEquals(CheckStatus.Passed, ui.stages.first().status)
    }

    @Test fun runningRunExposesCancelRequestAndCurrentApp() {
        val run = BuildRunState(phase = BuildRunPhase.Running, stage = BuildStage.Sign, cancelRequested = true)
        val ui = run.toUiState(3, false, app(1, 3))
        assertTrue(ui.running)
        assertTrue(ui.cancelRequested)
        assertEquals(1, ui.currentApp?.number)
        assertEquals(BuildStage.Sign, ui.runningStage)
    }
}
