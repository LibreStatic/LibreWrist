package io.openshm.patcher

import io.openshm.patcher.ui.components.RunState
import io.openshm.patcher.ui.components.UnattendedPhase
import io.openshm.patcher.ui.components.UnattendedRunMachine
import io.openshm.patcher.ui.nav.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class UnattendedRunTrackerTest {

    private fun started(): UnattendedRunMachine = UnattendedRunMachine().apply {
        onRoute(true, Route.Welcome)
        onRoute(true, Route.PhoneAdb)
    }

    @Test
    fun startsWhenLeavingWelcomeAndShowsTheIntro() {
        val m = started()
        assertEquals(RunState.Running, m.state.value)
        assertEquals(UnattendedPhase.Intro, m.phase.value)
    }

    @Test
    fun staysRunningAcrossStepsAndCollapsesToBadge() {
        val m = started()
        m.introFinished()
        for (r in listOf(Route.Profile, Route.Acquire, Route.GuidedBrowser, Route.Acquire, Route.Settings)) {
            m.onRoute(true, r)
            assertEquals(RunState.Running, m.state.value)
            assertEquals(UnattendedPhase.Badge, m.phase.value)
        }
    }

    @Test
    fun finishesOnAllSet() {
        val m = started()
        m.onRoute(true, Route.SelfTest)
        m.onRoute(true, Route.AllSet)
        assertEquals(RunState.Idle, m.state.value)
        assertEquals(UnattendedPhase.None, m.phase.value)
    }

    @Test
    fun finishesOnHome() {
        val m = started()
        m.onRoute(true, Route.Build)
        m.onRoute(true, Route.Home)
        assertEquals(RunState.Idle, m.state.value)
        assertEquals(UnattendedPhase.None, m.phase.value)
    }

    @Test
    fun failEndsTheRunAndItDoesNotRestartMidFlow() {
        val m = started()
        m.fail()
        assertEquals(RunState.Idle, m.state.value)
        m.onRoute(true, Route.Build)
        m.onRoute(true, Route.InstallPhone)
        assertEquals(RunState.Idle, m.state.value)
    }

    @Test
    fun switchingFreeRunOffCancelsAtOnce() {
        val m = started()
        m.onFreeRun(false)
        assertEquals(RunState.Idle, m.state.value)
    }

    @Test
    fun noStartWithFreeRunOffOrFromWelcomeToHome() {
        val off = UnattendedRunMachine()
        off.onRoute(false, Route.Welcome)
        off.onRoute(false, Route.Profile)
        assertEquals(RunState.Idle, off.state.value)
        val home = UnattendedRunMachine()
        home.onRoute(true, Route.Welcome)
        home.onRoute(true, Route.Home)
        assertEquals(RunState.Idle, home.state.value)
        home.onRoute(true, Route.Welcome)
        assertEquals(RunState.Idle, home.state.value)
    }

    @Test
    fun aNewStartRearmsTheIntro() {
        val m = started()
        m.introFinished()
        m.fail()
        m.onRoute(true, Route.Welcome)
        m.onRoute(true, Route.PhoneAdb)
        assertEquals(RunState.Running, m.state.value)
        assertEquals(UnattendedPhase.Intro, m.phase.value)
    }

    @Test
    fun attentionShowsTheDialogThenThePausedBadgeAndResumes() {
        val m = started()
        m.introFinished()
        val step = Any()
        m.requestAttention(step)
        assertEquals(UnattendedPhase.Attention, m.phase.value)
        m.dismissAttention()
        assertEquals(UnattendedPhase.Paused, m.phase.value)
        m.reopenAttention()
        assertEquals(UnattendedPhase.Attention, m.phase.value)
        m.dismissAttention()
        m.releaseAttention(step)
        assertEquals(RunState.Running, m.state.value)
        assertEquals(UnattendedPhase.Badge, m.phase.value)
        // A new wait asks again even though the previous one was dismissed.
        m.requestAttention(step)
        assertEquals(UnattendedPhase.Attention, m.phase.value)
    }

    @Test
    fun attentionDuringTheIntroWaitsForIt() {
        val m = started()
        m.requestAttention(Any())
        assertEquals(UnattendedPhase.Intro, m.phase.value)
        m.introFinished()
        assertEquals(UnattendedPhase.Attention, m.phase.value)
    }

    @Test
    fun attentionIsIgnoredOutsideARunAndClearedByFailure() {
        val idle = UnattendedRunMachine()
        idle.requestAttention(Any())
        assertEquals(UnattendedPhase.None, idle.phase.value)

        val m = started()
        m.introFinished()
        m.requestAttention(Any())
        m.fail()
        assertEquals(RunState.Idle, m.state.value)
        assertEquals(UnattendedPhase.None, m.phase.value)
    }

    @Test
    fun theRunWaitsUntilEveryHolderIsDone() {
        val m = started()
        m.introFinished()
        val a = Any()
        val b = Any()
        m.requestAttention(a)
        m.requestAttention(b)
        m.releaseAttention(a)
        assertEquals(UnattendedPhase.Attention, m.phase.value)
        m.releaseAttention(b)
        assertEquals(UnattendedPhase.Badge, m.phase.value)
    }
}
