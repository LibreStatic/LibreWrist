package io.openshm.patcher.revert

import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.screens.revert.Presence
import io.openshm.patcher.ui.screens.revert.RevertAction
import io.openshm.patcher.ui.screens.revert.RevertEvent
import io.openshm.patcher.ui.screens.revert.RevertItem
import io.openshm.patcher.ui.screens.revert.RevertPhase
import io.openshm.patcher.ui.screens.revert.RevertProbe
import io.openshm.patcher.ui.screens.revert.RevertReport
import io.openshm.patcher.ui.screens.revert.RevertScope
import io.openshm.patcher.ui.screens.revert.RevertUiState
import io.openshm.patcher.ui.screens.revert.SelfUninstallResult
import io.openshm.patcher.ui.screens.revert.StepOutcome
import io.openshm.patcher.ui.screens.revert.WatchReach
import io.openshm.patcher.ui.screens.revert.WatchStatus
import io.openshm.patcher.ui.screens.revert.reduce
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.openshm.patcher.TestCatalogue

class RevertStateTest {
    @Before fun catalogue() = TestCatalogue.install()

    private val watchApp = RevertItem(Device.Watch, "Bridge", "a.b.watch", RevertAction.Uninstall)
    private val phoneApp = RevertItem(Device.Phone, "Bridge", "a.b.phone", RevertAction.Uninstall)
    private fun probed(vararg items: RevertItem, watch: WatchReach = WatchReach.Reached) =
        RevertUiState().reduce(RevertEvent.Probed(RevertProbe(items.toList(), watch), null))

    @Test
    fun probingEndsInReviewWithTheActionableRowsOnly() {
        val gone = phoneApp.copy(pkg = "x.y.z", presence = Presence.Absent)
        val s = probed(watchApp, phoneApp, gone)
        assertEquals(RevertPhase.Review, s.phase)
        assertEquals(2, s.rows.size)
        assertEquals(listOf(gone), s.notInstalled)
        assertTrue(s.canRevert)
    }

    @Test
    fun nothingToDoWithTheWatchCheckedIsCompleteWithoutAReview() {
        val s = probed()
        assertEquals(RevertPhase.Done, s.phase)
        assertTrue(s.complete)
    }

    @Test
    fun anUnreachableWatchWaitsForADecisionAndBlocksTheRevert() {
        val s = probed(phoneApp, watch = WatchReach.Unreachable(IOException("x")))
        assertEquals(RevertPhase.Review, s.phase)
        assertTrue(s.watchNeedsDecision)
        assertFalse(s.canRevert)
        assertEquals(s, s.reduce(RevertEvent.AskRevert))
        val skipped = s.reduce(RevertEvent.SkipWatch)
        assertEquals(WatchStatus.Skipped, skipped.watch)
        assertTrue(skipped.canRevert)
    }

    @Test
    fun skippingTheWatchWithNothingOnThePhoneEndsPartial() {
        val s = probed(watch = WatchReach.Unreachable(IOException("x"))).reduce(RevertEvent.SkipWatch)
        assertEquals(RevertPhase.Done, s.phase)
        assertFalse(s.complete)
    }

    @Test
    fun confirmStartStepAndFinishWalkThePhases() {
        var s = probed(watchApp, phoneApp).reduce(RevertEvent.AskRevert)
        assertTrue(s.confirming)
        s = s.reduce(RevertEvent.Start)
        assertEquals(RevertPhase.Running, s.phase)
        assertFalse(s.confirming)
        s = s.reduce(RevertEvent.Step(watchApp.key, StepOutcome.Done))
        assertEquals(StepOutcome.Done, s.rows.first { it.item == watchApp }.outcome)
        assertNull(s.rows.first { it.item == phoneApp }.outcome)
        val done = s.reduce(RevertEvent.Finished(RevertReport(mapOf(watchApp.key to StepOutcome.Done, phoneApp.key to StepOutcome.Cancelled), true)))
        assertEquals(RevertPhase.Done, done.phase)
        assertTrue(done.partial)
    }

    @Test
    fun aCompleteReportMakesTheStateComplete() {
        val s = probed(phoneApp).reduce(RevertEvent.Start)
            .reduce(RevertEvent.Finished(RevertReport(mapOf(phoneApp.key to StepOutcome.Done), true)))
        assertTrue(s.complete)
    }

    @Test
    fun theWatchLostMidRunShowsAsUnreachable() {
        val s = probed(watchApp).reduce(RevertEvent.Start).reduce(RevertEvent.Finished(RevertReport(emptyMap(), false)))
        assertEquals(WatchStatus.Unreachable, s.watch)
        assertTrue(s.partial)
    }

    @Test
    fun selfUninstallIsOfferedOnlyAfterTheRunAndACancelIsRemembered() {
        val review = probed(phoneApp)
        assertEquals(review, review.reduce(RevertEvent.AskSelfUninstall))
        var s = review.reduce(RevertEvent.Start).reduce(RevertEvent.Finished(RevertReport(emptyMap(), true)))
        s = s.reduce(RevertEvent.AskSelfUninstall)
        assertTrue(s.confirmingSelfUninstall)
        s = s.reduce(RevertEvent.ConfirmSelfUninstall).reduce(RevertEvent.SelfUninstallEnded(SelfUninstallResult.Cancelled))
        assertFalse(s.confirmingSelfUninstall)
        assertEquals(SelfUninstallResult.Cancelled, s.selfUninstall)
    }

    @Test
    fun reprobeGoesBackToProbing() {
        val s = probed(phoneApp).reduce(RevertEvent.Reprobe)
        assertEquals(RevertPhase.Probing, s.phase)
    }

    @Test
    fun aWatchNotNeededCountsAsCompleteWhenThereIsNothingToDo() {
        val s = RevertUiState(scope = RevertScope.Output("bridge-phone"))
            .reduce(RevertEvent.Probed(RevertProbe(emptyList(), WatchReach.NotNeeded), null))
        assertEquals(WatchStatus.NotNeeded, s.watch)
        assertEquals(RevertPhase.Done, s.phase)
        assertTrue(s.complete)
    }

    @Test
    fun aWatchNotNeededDoesNotBlockTheRevert() {
        val s = RevertUiState(scope = RevertScope.Output("bridge-phone"))
            .reduce(RevertEvent.Probed(RevertProbe(listOf(phoneApp), WatchReach.NotNeeded), null))
        assertTrue(s.canRevert)
        assertFalse(s.watchNeedsDecision)
    }

    @Test
    fun theWatchCannotBeSkippedInAScopedRun() {
        for (scope in listOf(RevertScope.Watch, RevertScope.Output("shm-watch"))) {
            val s = RevertUiState(scope = scope)
                .reduce(RevertEvent.Probed(RevertProbe(listOf(watchApp), WatchReach.Unreachable(IOException("off"))), null))
            assertFalse(s.canSkipWatch)
            assertEquals(s, s.reduce(RevertEvent.SkipWatch))
        }
        assertTrue(RevertUiState().canSkipWatch)
    }
}
