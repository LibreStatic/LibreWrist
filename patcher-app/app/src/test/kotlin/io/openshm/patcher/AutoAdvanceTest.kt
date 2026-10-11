package io.openshm.patcher

import io.openshm.patcher.ui.screens.profile.AutoAdvance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAdvanceTest {
    @Test
    fun aDetectionThatArrivesWhileOpenAdvancesOnce() {
        val a = AutoAdvance(initiallyDetected = false) { false }
        assertFalse(a.detectionArrived(false))
        assertTrue(a.detectionArrived(true))
        assertFalse(a.detectionArrived(true))
    }

    @Test
    fun aDetectionAlreadyThereWhenOpenedNeverAdvances() {
        val a = AutoAdvance(initiallyDetected = true) { false }
        assertFalse(a.detectionArrived(true))
    }

    @Test
    fun manualModeNeverAdvances() {
        val a = AutoAdvance(initiallyDetected = false) { true }
        assertFalse(a.detectionArrived(true))
    }

    @Test
    fun touchingTheSelectionFirstPreventsIt() {
        val a = AutoAdvance(initiallyDetected = false) { false }
        a.touched()
        assertFalse(a.detectionArrived(true))
    }
}
