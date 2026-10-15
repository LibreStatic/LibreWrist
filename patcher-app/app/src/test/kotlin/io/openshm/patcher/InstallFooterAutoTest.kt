package io.openshm.patcher

import io.openshm.patcher.ui.screens.install.InstallUiState
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.installFooterAuto
import io.openshm.patcher.ui.screens.install.installNeedsUser
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class InstallFooterAutoTest {
    @Test
    fun startIsAutoWithoutError() {
        assertTrue(installFooterAuto(InstallUiState(), phone = false))
    }

    @Test
    fun retryIsNeverAuto() {
        assertFalse(installFooterAuto(InstallUiState(errorRes = R.string.action_retry), phone = false))
    }

    @Test
    fun waitsForTheUserOnlyWhereNothingRunsByItself() {
        assertFalse(installNeedsUser(InstallUiState(), phone = true))
        assertFalse(installNeedsUser(InstallUiState(running = true, needsUnknownSources = true), phone = false))
        assertTrue(installNeedsUser(InstallUiState(needsUnknownSources = true), phone = false))
        // An error ends the run instead of pausing it.
        assertFalse(installNeedsUser(InstallUiState(needsUnknownSources = true, errorRes = R.string.action_retry), phone = false))
        for (phase in SelfAdbPhase.entries) {
            val expected = phase in setOf(SelfAdbPhase.WaitingForService, SelfAdbPhase.WaitingForCode, SelfAdbPhase.WrongCode, SelfAdbPhase.Ambiguous)
            assertEquals(phase.name, expected, installNeedsUser(InstallUiState(selfAdb = phase), phone = true))
        }
    }
}
