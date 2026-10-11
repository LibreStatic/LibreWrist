package io.openshm.patcher

import io.openshm.patcher.ui.screens.watchpair.WatchPairEvent
import io.openshm.patcher.ui.screens.watchpair.WatchPairUiState
import io.openshm.patcher.ui.screens.watchpair.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code entry on the pairing screen: it starts pairing by itself, exactly once per complete code. */
class WatchPairCodeTest {
    private val found = WatchPairUiState(foundAddress = "192.0.2.7:37099")

    private fun WatchPairUiState.type(digits: String): WatchPairUiState =
        digits.fold(this) { state, digit -> state.reduce(WatchPairEvent.CodeChanged(state.code + digit)) }

    @Test
    fun theSixthDigitStartsPairingOnce() {
        val typing = found.type("12345")
        assertFalse(typing.pairing)
        val started = typing.reduce(WatchPairEvent.CodeChanged("123456"))
        assertTrue(started.pairing)
        assertEquals("123456", started.code)
    }

    @Test
    fun reEmittingTheCompleteCodeDoesNotStartAnotherAttempt() {
        val started = found.type("123456")
        assertTrue(started.pairing)
        // While pairing everything is ignored, so a duplicated callback changes nothing.
        assertEquals(started, started.reduce(WatchPairEvent.CodeChanged("123456")))
        // Even once the attempt ended without clearing the code, the same value is not a new completion.
        val idle = started.copy(pairing = false)
        assertFalse(idle.reduce(WatchPairEvent.CodeChanged("123456")).pairing)
    }

    @Test
    fun aSeventhDigitDoesNotStartAnything() {
        val idle = found.type("123456").copy(pairing = false)
        val seventh = idle.reduce(WatchPairEvent.CodeChanged("1234567"))
        assertEquals("123456", seventh.code)
        assertFalse(seventh.pairing)
    }

    @Test
    fun pastingSixDigitsAtOnceStartsPairing() {
        assertTrue(found.reduce(WatchPairEvent.CodeChanged("654321")).pairing)
        assertTrue(found.reduce(WatchPairEvent.CodeChanged("65 43-21")).pairing)
    }

    @Test
    fun noAutoStartWithoutAUsableAddressAndThePairButtonWorksOnceItIsFixed() {
        val manual = WatchPairUiState(manual = true, host = "bad host", port = "37099")
        val typed = manual.type("123456")
        assertTrue(typed.codeComplete)
        assertFalse(typed.pairing)
        assertFalse(typed.canPair)
        val fixed = typed.reduce(WatchPairEvent.HostChanged("192.0.2.7"))
        assertTrue(fixed.canPair)
        assertFalse(fixed.pairing)
        assertTrue(fixed.reduce(WatchPairEvent.Pair).pairing)
    }

    @Test
    fun noAutoStartBeforeTheWatchIsFound() {
        assertFalse(WatchPairUiState().type("123456").pairing)
    }

    @Test
    fun codeChangesAreIgnoredWhilePairing() {
        val pairing = found.type("123456")
        assertEquals(pairing, pairing.reduce(WatchPairEvent.CodeChanged("")))
    }

    @Test
    fun aNewCompleteCodeAfterAFailureStartsANewAttempt() {
        val failed = found.type("123456").copy(pairing = false, code = "", failures = 1, errorRes = R.string.watchpair_error_wrong_code)
        // The error stays until the first new digit.
        assertEquals(R.string.watchpair_error_wrong_code, failed.errorRes)
        val typing = failed.type("1")
        assertEquals(null, typing.errorRes)
        assertFalse(typing.pairing)
        val again = typing.type("23456")
        assertTrue(again.pairing)
        assertEquals(1, again.failures)
    }
}
