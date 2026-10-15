package io.openshm.patcher

import io.openshm.patcher.data.AutomationState
import org.junit.Test
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
