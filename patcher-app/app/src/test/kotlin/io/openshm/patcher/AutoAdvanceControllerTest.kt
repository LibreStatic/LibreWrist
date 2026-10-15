package io.openshm.patcher

import io.openshm.patcher.ui.components.AutoAdvanceController
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AutoAdvanceControllerTest {
    @Test
    fun inputPausesOnlyWhileACountdownIsFilling() {
        val c = AutoAdvanceController()
        c.beginCountdown()
        c.onUserInput()
        assertTrue(c.paused)
    }

    @Test
    fun inputWithoutACountdownDoesNothing() {
        val c = AutoAdvanceController()
        c.onUserInput()
        assertFalse(c.paused)
        c.beginCountdown()
        c.endCountdown()
        c.onUserInput()
        assertFalse(c.paused)
    }

    @Test
    fun routeChangeLiftsThePauseButSameRouteDoesNot() {
        val c = AutoAdvanceController()
        c.onRouteChanged("a")
        c.beginCountdown()
        c.onUserInput()
        c.onRouteChanged("a")
        assertTrue(c.paused)
        c.onRouteChanged("b")
        assertFalse(c.paused)
    }

    @Test
    fun nestedCountdownsAreCounted() {
        val c = AutoAdvanceController()
        c.beginCountdown()
        c.beginCountdown()
        assertEquals(2, c.counting)
        c.endCountdown()
        c.onUserInput()
        assertTrue(c.paused)
        c.endCountdown()
        c.endCountdown()
        assertEquals(0, c.counting)
    }
}
