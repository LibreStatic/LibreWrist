package io.openshm.patcher

import io.openshm.patcher.data.ADVANCE_SECONDS_DEFAULT
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.AutomationState
import io.openshm.patcher.data.chinaModeActive
import io.openshm.patcher.data.chinaModeToggleVisible
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun guidePetIsOnByDefaultAndIndependentOfUnattended() {
        assertTrue(AutomationState().guidePet)
        try {
            Automation.setUnattended(false)
            assertTrue(Automation.state.value.guidePet)
            Automation.setGuidePet(false)
            assertFalse(Automation.state.value.guidePet)
            assertFalse(Automation.state.value.unattended)
        } finally {
            Automation.setGuidePet(true)
            Automation.setUnattended(true)
        }
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

    @Test
    fun chinaModeFollowsDetectionUntilTheUserChooses() {
        assertNull(AutomationState().chinaMode)
        assertTrue(AutomationState(chinaMode = null).chinaModeActive(detected = true))
        assertFalse(AutomationState(chinaMode = null).chinaModeActive(detected = false))
        assertTrue(AutomationState(chinaMode = true).chinaModeActive(detected = true))
        assertTrue(AutomationState(chinaMode = true).chinaModeActive(detected = false))
        assertFalse(AutomationState(chinaMode = false).chinaModeActive(detected = true))
        assertFalse(AutomationState(chinaMode = false).chinaModeActive(detected = false))
    }

    @Test
    fun chinaModeSwitchShowsWhereDetectedOrOnceSet() {
        assertFalse(chinaModeToggleVisible(detected = false, stored = null))
        assertTrue(chinaModeToggleVisible(detected = true, stored = null))
        assertTrue(chinaModeToggleVisible(detected = false, stored = true))
        assertTrue(chinaModeToggleVisible(detected = false, stored = false))
        assertTrue(chinaModeToggleVisible(detected = true, stored = false))
    }

    @Test
    fun setChinaModeStoresTheChoice() {
        try {
            Automation.setChinaMode(false)
            assertEquals(false, Automation.state.value.chinaMode)
            Automation.setChinaMode(true)
            assertEquals(true, Automation.state.value.chinaMode)
        } finally {
            Automation.setChinaMode(false)
        }
    }
}
