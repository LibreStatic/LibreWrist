package io.openshm.patcher

import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.device.WatchAnnouncements
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WatchAnnouncementsTest {
    private val first = DetectedWatch(33, null, DetectedWatch.Source.Network, "SM-R870")
    private val second = DetectedWatch(36, null, DetectedWatch.Source.Network, "SM-R920")

    @Before
    @After
    fun clean() = WatchAnnouncements.reset()

    @Test
    fun keepsListeningAndRemembersTheLatestWatch() = runBlocking {
        val source = MutableSharedFlow<DetectedWatch?>()
        WatchAnnouncements.start("a") { source }
        withTimeout(5_000) { WatchAnnouncements.running.first { it } }
        withTimeout(5_000) { while (source.subscriptionCount.value == 0) kotlinx.coroutines.delay(5) }
        source.emit(null)
        source.emit(first)
        withTimeout(5_000) { WatchAnnouncements.state.first { it == first } }
        source.emit(second)
        withTimeout(5_000) { WatchAnnouncements.state.first { it == second } }
        source.emit(null)
        assertEquals(second, WatchAnnouncements.state.value)
    }

    @Test
    fun runsWhileAnyHolderIsStarted() = runBlocking {
        val source = MutableSharedFlow<DetectedWatch?>()
        WatchAnnouncements.start("a") { source }
        WatchAnnouncements.start("a") { error("already running") }
        WatchAnnouncements.start("b") { error("already running") }
        WatchAnnouncements.stop("a")
        assertTrue(WatchAnnouncements.running.value)
        WatchAnnouncements.stop("b")
        assertFalse(WatchAnnouncements.running.value)
    }

    @Test
    fun aFailingDiscoveryIsSilent() = runBlocking {
        WatchAnnouncements.start("a") { flow { throw IllegalStateException("no nsd") } }
        withTimeout(5_000) { WatchAnnouncements.running.first { !it } }
        assertNull(WatchAnnouncements.state.value)
    }
}
