package io.openshm.patcher

import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.ui.screens.watchpair.WatchPairEvent
import io.openshm.patcher.ui.screens.watchpair.WatchPairUiState
import io.openshm.patcher.ui.screens.watchpair.WatchProfileCorrection
import io.openshm.patcher.ui.screens.watchpair.announcedModel
import io.openshm.patcher.ui.screens.watchpair.freshReconnectTargets
import io.openshm.patcher.ui.screens.watchpair.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reusing the pairing of a remembered watch: the screen state and the retry decision. */
class WatchPairReconnectTest {
    private fun service(name: String, host: String, port: Int, model: String? = null) =
        AdbService(name, host, port, AdbServiceType.CONNECT, if (model == null) emptyMap() else mapOf("name" to model))

    @Test
    fun reconnectingShowsUntilItEnds() {
        val started = WatchPairUiState().reduce(WatchPairEvent.ReconnectStarted)
        assertTrue(started.reconnecting)
        val failed = started.reduce(WatchPairEvent.ReconnectFailed(notFound = true))
        assertFalse(failed.reconnecting)
        assertTrue(failed.rememberedNotFound)
    }

    @Test
    fun aRejectedKeyDropsSilentlyToThePairingUi() {
        val failed = WatchPairUiState(reconnecting = true).reduce(WatchPairEvent.ReconnectFailed(notFound = false))
        assertEquals(WatchPairUiState(), failed)
    }

    @Test
    fun aQuietRetryFailureKeepsTheHint() {
        val hinted = WatchPairUiState(rememberedNotFound = true).reduce(WatchPairEvent.ReconnectFailed(notFound = false))
        assertTrue(hinted.rememberedNotFound)
    }

    @Test
    fun reconnectingReachesThePairedOutcome() {
        val done = WatchPairUiState(reconnecting = true, rememberedNotFound = true)
            .reduce(WatchPairEvent.Reconnected("SM-R920", null))
        assertTrue(done.paired)
        assertTrue(done.reconnected)
        assertEquals("SM-R920", done.reconnectedModel)
        assertFalse(done.reconnecting)
        assertFalse(done.rememberedNotFound)
        assertNull(done.correction)
    }

    @Test
    fun aProfileDisagreementWaitsForConfirmation() {
        val correction = WatchProfileCorrection(WatchProfile.WearOs5, "15")
        val done = WatchPairUiState().reduce(WatchPairEvent.Reconnected(null, correction))
        assertFalse(done.paired)
        assertEquals(correction, done.correction)
        val confirmed = done.reduce(WatchPairEvent.ConfirmCorrection)
        assertTrue(confirmed.paired)
    }

    @Test
    fun aCodePairingInProgressIsNotOverriddenByAReconnect() {
        val pairing = WatchPairUiState(pairing = true, reconnecting = true)
        val after = pairing.reduce(WatchPairEvent.Reconnected("SM-R920", null))
        assertTrue(after.pairing)
        assertFalse(after.paired)
        assertFalse(after.reconnected)
        assertFalse(after.reconnecting)
        assertEquals(pairing.copy(reconnecting = false), pairing.reduce(WatchPairEvent.ReconnectFailed(false)))
    }

    @Test
    fun eachNewPortOfTheRememberedHostIsRetriedOnce() {
        val tried = mutableSetOf<String>()
        val list = listOf(service("adb-R1-abc", "192.168.0.244", 36029), service("other", "192.168.0.9", 40000))
        val first = freshReconnectTargets(list, "192.168.0.244", "adb-R1-", tried)
        assertEquals(listOf("192.168.0.244:36029"), first)
        tried += first
        assertTrue(freshReconnectTargets(list, "192.168.0.244", "adb-R1-", tried).isEmpty())
        val moved = list + service("adb-R1-abc", "192.168.0.244", 41000)
        assertEquals(listOf("192.168.0.244:41000"), freshReconnectTargets(moved, "192.168.0.244", "adb-R1-", tried))
    }

    @Test
    fun aWatchWithANewAddressIsFoundBySerialPrefix() {
        val list = listOf(service("adb-R1-xyz", "192.168.0.50", 37000), service("adb-OTHER-q", "192.168.0.51", 37001))
        assertEquals(listOf("192.168.0.50:37000"), freshReconnectTargets(list, "192.168.0.244", "adb-R1-", emptySet()))
        assertTrue(freshReconnectTargets(list, "192.168.0.244", null, emptySet()).isEmpty())
    }

    @Test
    fun theModelComesFromTheRememberedWatchAnnouncement() {
        val list = listOf(service("adb-R1-abc", "192.168.0.244", 36029, "SM-R920"), service("o", "192.168.0.9", 1, "Pixel"))
        assertEquals("SM-R920", announcedModel(list, "192.168.0.244", "adb-R1-"))
        assertNull(announcedModel(listOf(service("adb-R1-abc", "192.168.0.244", 36029)), "192.168.0.244", null))
        assertNull(announcedModel(emptyList(), "192.168.0.244", null))
    }
}
