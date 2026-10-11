package io.openshm.patcher

import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.WatchDetection
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.SetupFlow
import io.openshm.patcher.ui.nav.SkippedStep
import io.openshm.patcher.ui.nav.skippableSteps
import io.openshm.patcher.ui.nav.skippedRunAfter
import io.openshm.patcher.ui.screens.welcome.SkipDialog
import io.openshm.patcher.ui.screens.welcome.SkipPreflight
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkipPreflightTest {
    private val watch = DetectedWatch(36, "16")

    private class Recorder {
        var applied: List<SkippedStep>? = null
        var appliedWatch: DetectedWatch? = null
        var went = 0
    }

    private fun TestScope.preflight(
        remembered: Boolean,
        detect: suspend () -> DetectedWatch?,
        wearableReady: Boolean,
        rec: Recorder,
    ): SkipPreflight {
        val p = SkipPreflight(
            backgroundScope,
            watchRemembered = { remembered },
            detect = detect,
            wearableReady = { wearableReady },
            apply = { steps, w -> rec.applied = steps; rec.appliedWatch = w },
        )
        backgroundScope.launch { p.go.collect { rec.went++ } }
        return p
    }

    @Test
    fun aWatchThatAnswersSkipsProfileAndPairingAndWatchPair() {
        val steps = skippableSteps(watch, wearableReady = true)
        assertEquals(listOf(Route.Profile, Route.WearablePairing, Route.WatchPair), steps.map { it.route })
        assertEquals(watch, steps.first().watch)
        assertEquals(Route.Acquire, SetupFlow.next(Route.Welcome, steps.map { it.route }.toSet()))
    }

    @Test
    fun withoutTheWatchOnlyAReadyGalaxyWearableIsSkipped() {
        val steps = skippableSteps(null, wearableReady = true)
        assertEquals(listOf(Route.WearablePairing), steps.map { it.route })
        val skipped = steps.map { it.route }.toSet()
        assertEquals(Route.Profile, SetupFlow.next(Route.Welcome, skipped))
        assertEquals(Route.WatchPair, SetupFlow.next(Route.Profile, skipped))
        assertTrue(skippableSteps(null, wearableReady = false).isEmpty())
    }

    @Test
    fun theDialogChecksThenWaitsForGotIt() = runTest {
        val rec = Recorder()
        val answer = CompletableDeferred<DetectedWatch?>()
        val p = preflight(remembered = true, detect = { answer.await() }, wearableReady = true, rec = rec)
        p.start()
        runCurrent()
        assertEquals(SkipDialog.Checking, p.dialog.value)
        answer.complete(watch)
        runCurrent()
        assertTrue(p.dialog.value is SkipDialog.Skipping)
        assertEquals(3, (p.dialog.value as SkipDialog.Skipping).steps.size)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(p.dialog.value is SkipDialog.Skipping)
        assertNull(rec.applied)
        assertEquals(0, rec.went)
        p.confirm()
        runCurrent()
        assertEquals(3, rec.applied!!.size)
        assertEquals(watch, rec.appliedWatch)
        assertEquals(1, rec.went)
        assertNull(p.dialog.value)
    }

    @Test
    fun nothingToSkipMovesOnAtOnceWithoutADialog() = runTest {
        val rec = Recorder()
        val p = preflight(remembered = false, detect = { error("not asked") }, wearableReady = false, rec = rec)
        p.start()
        runCurrent()
        assertNull(p.dialog.value)
        assertEquals(emptyList<SkippedStep>(), rec.applied)
        assertEquals(1, rec.went)
    }

    @Test
    fun aWatchThatDoesNotAnswerSkipsNothingOfItsOwn() = runTest {
        val rec = Recorder()
        val p = preflight(remembered = true, detect = { null }, wearableReady = false, rec = rec)
        p.start()
        runCurrent()
        assertEquals(emptyList<SkippedStep>(), rec.applied)
        assertEquals(1, rec.went)
        assertNull(p.dialog.value)
    }

    @Test
    fun backOnTheDialogSkipsNothing() = runTest {
        val rec = Recorder()
        val p = preflight(remembered = true, detect = { watch }, wearableReady = true, rec = rec)
        p.start()
        runCurrent()
        assertTrue(p.dialog.value is SkipDialog.Skipping)
        p.cancel()
        p.confirm()
        runCurrent()
        assertNull(p.dialog.value)
        assertNull(rec.applied)
        assertEquals(0, rec.went)
    }

    @Test
    fun onlyTheRunRightAfterAStepIsAnnounced() {
        val wearableOnly = skippableSteps(null, wearableReady = true)
        assertTrue(skippedRunAfter(Route.Welcome, wearableOnly).isEmpty())
        assertEquals(listOf(Route.WearablePairing), skippedRunAfter(Route.Profile, wearableOnly).map { it.route })
        val all = skippableSteps(watch, wearableReady = true)
        assertEquals(3, skippedRunAfter(Route.Welcome, all).size)
        assertTrue(skippedRunAfter(Route.WatchPair, all).isEmpty())
    }

    @Test
    fun aLaterSkipIsRecordedWithoutADialogOnTheWelcome() = runTest {
        val rec = Recorder()
        val p = preflight(remembered = false, detect = { error("not asked") }, wearableReady = true, rec = rec)
        p.start()
        runCurrent()
        assertNull(p.dialog.value)
        assertEquals(listOf(Route.WearablePairing), rec.applied!!.map { it.route })
        assertEquals(1, rec.went)
    }

    @Test
    fun setUpManuallySkipsNothingButKeepsTheDetectedWatch() = runTest {
        val rec = Recorder()
        val p = preflight(remembered = true, detect = { watch }, wearableReady = true, rec = rec)
        p.start()
        runCurrent()
        assertTrue(p.dialog.value is SkipDialog.Skipping)
        p.manual()
        p.confirm()
        runCurrent()
        assertEquals(emptyList<SkippedStep>(), rec.applied)
        assertEquals(watch, rec.appliedWatch)
        assertEquals(1, rec.went)
        assertNull(p.dialog.value)
    }

    @Test
    fun aSecondTapWhileRunningIsIgnored() = runTest {
        val rec = Recorder()
        var asked = 0
        val p = preflight(remembered = true, detect = { asked++; watch }, wearableReady = false, rec = rec)
        p.start()
        p.start()
        runCurrent()
        p.start()
        p.confirm()
        p.confirm()
        runCurrent()
        assertEquals(1, asked)
        assertEquals(1, rec.went)
    }

    @Test
    fun detectionRunsOnceAndKeepsTheAnswer() = runBlocking {
        WatchDetection.reset()
        try {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            WatchDetection.start(true) { calls++; gate.await(); watch }
            WatchDetection.start(true) { calls++; watch }
            assertTrue(WatchDetection.state.value.running)
            gate.complete(Unit)
            val done = withTimeout(5_000) { WatchDetection.state.first { it.done } }
            assertEquals(watch, done.watch)
            WatchDetection.start(true) { calls++; null }
            assertEquals(1, calls)
        } finally {
            WatchDetection.reset()
        }
    }

    @Test
    fun noRememberedWatchAsksNothingAndAFailureMayBeRetried() = runBlocking {
        WatchDetection.reset()
        try {
            WatchDetection.start(false) { error("not asked") }
            assertFalse(WatchDetection.state.value.running)
            WatchDetection.start(true) { throw IllegalStateException("offline") }
            val failed = withTimeout(5_000) { WatchDetection.state.first { it.done } }
            assertNull(failed.watch)
            WatchDetection.start(true) { DetectedWatch(34, "14") }
            val retried = withTimeout(5_000) { WatchDetection.state.first { it.watch != null } }
            assertEquals(34, retried.watch!!.sdk)
        } finally {
            WatchDetection.reset()
        }
    }
}
