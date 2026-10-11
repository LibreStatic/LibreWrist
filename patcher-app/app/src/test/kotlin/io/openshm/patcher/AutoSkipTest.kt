package io.openshm.patcher

import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.WatchDetection
import io.openshm.patcher.ui.screens.profile.AUTO_SKIP_HOLD_MILLIS
import io.openshm.patcher.ui.screens.profile.AutoSkip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
class AutoSkipTest {
    @Test
    fun detectionShowsTheNoticeThenMovesOn() = runTest {
        val skip = AutoSkip(backgroundScope)
        var went = 0
        backgroundScope.launch { skip.go.collect { went++ } }
        skip.onDetected()
        assertTrue(skip.skipping.value)
        advanceTimeBy(AUTO_SKIP_HOLD_MILLIS - 1)
        runCurrent()
        assertEquals(0, went)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, went)
    }

    @Test
    fun aTouchBeforeTheAnswerKeepsTheStep() = runTest {
        val skip = AutoSkip(backgroundScope)
        var went = 0
        backgroundScope.launch { skip.go.collect { went++ } }
        skip.disarm()
        skip.onDetected()
        advanceTimeBy(AUTO_SKIP_HOLD_MILLIS * 2)
        runCurrent()
        assertFalse(skip.skipping.value)
        assertEquals(0, went)
    }

    @Test
    fun comingBackNeverSkipsAgain() = runTest {
        val skip = AutoSkip(backgroundScope)
        var went = 0
        backgroundScope.launch { skip.go.collect { went++ } }
        skip.onDetected()
        advanceTimeBy(AUTO_SKIP_HOLD_MILLIS + 1)
        runCurrent()
        skip.onShown()
        assertFalse(skip.skipping.value)
        skip.onDetected()
        advanceTimeBy(AUTO_SKIP_HOLD_MILLIS * 2)
        runCurrent()
        assertEquals(1, went)
    }

    @Test
    fun shownBeforeTheHoldEndsKeepsTheNotice() = runTest {
        val skip = AutoSkip(backgroundScope)
        skip.onDetected()
        skip.onShown()
        assertTrue(skip.skipping.value)
    }

    @Test
    fun detectionRunsOnceAndKeepsTheAnswer() = runBlocking {
        WatchDetection.reset()
        try {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            val watch = DetectedWatch(36, "16")
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
