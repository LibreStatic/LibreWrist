package io.openshm.patcher

import io.openshm.patcher.data.ADVANCE_SECONDS_DEFAULT
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.AutomationState
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AutomationStateTest {
    @Test
    fun defaultsAreUnattendedOnAndTheRestOff() {
        val s = AutomationState()
        assertTrue(s.unattended)
        assertFalse(s.freeRun)
        assertFalse(s.uninstallMismatch)
        assertFalse(s.freeRunWarningHidden)
        assertFalse(s.freeRunActive)
        assertFalse(s.uninstallActive)
        assertEquals(5, s.advanceSeconds)
    }

    @Test
    fun advanceSecondsIsCoercedIntoRange() {
        try {
            Automation.setAdvanceSeconds(1)
            assertEquals(2, Automation.state.value.advanceSeconds)
            Automation.setAdvanceSeconds(99)
            assertEquals(60, Automation.state.value.advanceSeconds)
            Automation.setAdvanceSeconds(17)
            assertEquals(17, Automation.state.value.advanceSeconds)
        } finally {
            Automation.setAdvanceSeconds(ADVANCE_SECONDS_DEFAULT)
        }
    }

    @Test
    fun dependentSwitchesActOffWhenUnattendedIsOff() {
        val s = AutomationState(unattended = false, freeRun = true, uninstallMismatch = true)
        assertFalse(s.freeRunActive)
        assertFalse(s.uninstallActive)
    }

    @Test
    fun dependentSwitchesActWhenUnattendedIsOn() {
        val s = AutomationState(unattended = true, freeRun = true, uninstallMismatch = true)
        assertTrue(s.freeRunActive)
        assertTrue(s.uninstallActive)
    }

    @Test
    fun appsNoticeShowsOnAFirstLaunchUntilAcknowledged() {
        assertTrue(AutomationState().appsNoticePending)
        assertFalse(AutomationState(appsNoticeSeen = true).appsNoticePending)
    }

    @Test
    fun appsNoticeWaitsWhileUnattendedIsOff() {
        assertFalse(AutomationState(unattended = false).appsNoticePending)
    }
}
