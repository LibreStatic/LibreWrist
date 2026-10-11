package io.openshm.patcher

import io.openshm.patcher.adb.AdbChannel
import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.adb.AdbTransport
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.adb.ShellResult
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbPurpose
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.wearable.ClearOutcome
import io.openshm.patcher.ui.screens.wearable.TroubleshootEvent
import io.openshm.patcher.ui.screens.wearable.TroubleshootModel
import io.openshm.patcher.ui.screens.wearable.clearSucceeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun fakeClient() = AdbClient(object : AdbTransport {
    override val isConnected = true
    override fun open(destination: String): AdbChannel = throw UnsupportedOperationException()
    override fun close() {}
})

class TroubleshootTest {
    private val client = fakeClient()
    private val ran = mutableListOf<AdbClient>()
    private var started = 0
    private var reply: () -> ShellResult = { ShellResult(0, "Success\n", "") }

    // Unconfined runs the launched work inline, so each step is settled when the call returns.
    private fun model() = TroubleshootModel(
        scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
        startAdb = { started++ },
        runClear = { ran += it; reply() },
    )

    @Before @After fun reset() = SelfAdbController.finish()

    private fun TroubleshootModel.connect() {
        SelfAdbController.onSession(SelfAdbState.Connected(client))
        onAdb(SelfAdbController.state.value)
    }

    @Test
    fun clearingNeverRunsWithoutTheDialogBeingConfirmed() {
        val m = model()
        m.onEvent(TroubleshootEvent.ClearConfirmed) // no dialog was shown
        assertFalse(m.state.value.pendingClear)
        assertFalse(SelfAdbController.state.value.active)

        m.onEvent(TroubleshootEvent.ClearClicked)
        assertTrue(m.state.value.confirming)
        assertFalse(m.state.value.pendingClear)
        assertFalse(SelfAdbController.state.value.active) // the click alone starts nothing

        m.onEvent(TroubleshootEvent.ClearDismissed)
        assertFalse(m.state.value.confirming)
        assertFalse(SelfAdbController.state.value.active)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun aConfirmedClearWaitsForTheConnectionThenRunsOnce() {
        val m = model()
        m.onEvent(TroubleshootEvent.ClearClicked)
        m.onEvent(TroubleshootEvent.ClearConfirmed)
        assertTrue(m.state.value.pendingClear)
        assertEquals(SelfAdbPurpose.Troubleshoot, SelfAdbController.state.value.purpose)
        assertEquals(1, started)
        assertTrue(ran.isEmpty())

        m.connect()
        m.onAdb(SelfAdbController.state.value) // a repeated snapshot does not run it again
        assertEquals(listOf(client), ran)
        assertEquals(ClearOutcome.Success, m.state.value.outcome)
        assertFalse(SelfAdbController.state.value.active)
    }

    @Test
    fun aConnectionThatIsAlreadyThereIsUsedRightAfterTheConfirmation() {
        val m = model()
        m.onEvent(TroubleshootEvent.PairAdb)
        SelfAdbController.onSession(SelfAdbState.Connected(client))
        // The collector has not seen the connection yet when the user confirms.
        m.onEvent(TroubleshootEvent.ClearClicked)
        m.onEvent(TroubleshootEvent.ClearConfirmed)
        assertEquals(1, ran.size)
        assertEquals(ClearOutcome.Success, m.state.value.outcome)
    }

    @Test
    fun pairingOnlyDropsTheConnectionAndClearsNothing() {
        val m = model()
        m.onEvent(TroubleshootEvent.PairAdb)
        m.connect()
        assertEquals(ClearOutcome.PairedOnly, m.state.value.outcome)
        assertTrue(ran.isEmpty())
        assertFalse(SelfAdbController.state.value.active)
    }

    @Test
    fun outputWithoutSuccessIsAFailureAndStillEndsTheAttempt() {
        reply = { ShellResult(0, "Failed\n", "") }
        val m = model()
        m.onEvent(TroubleshootEvent.ClearClicked)
        m.onEvent(TroubleshootEvent.ClearConfirmed)
        m.connect()
        assertEquals(ClearOutcome.Failed, m.state.value.outcome)
        assertEquals(R.string.troubleshoot_clear_failed, m.state.value.errorRes)
        assertEquals("Failed", m.state.value.detail)
        assertFalse(SelfAdbController.state.value.active)
    }

    @Test
    fun anExceptionIsAFailureWithItsMessageAndEndsTheAttempt() {
        reply = { throw java.io.IOException("connection reset") }
        val m = model()
        m.onEvent(TroubleshootEvent.ClearClicked)
        m.onEvent(TroubleshootEvent.ClearConfirmed)
        m.connect()
        assertEquals(ClearOutcome.Failed, m.state.value.outcome)
        assertEquals("connection reset", m.state.value.detail)
        assertFalse(SelfAdbController.state.value.active)
    }

    @Test
    fun successIsJudgedByTheOutputNotTheExitCode() {
        assertTrue(clearSucceeded(ShellResult(0, "Success", "")))
        assertTrue(clearSucceeded(ShellResult(1, "", "Success")))
        assertFalse(clearSucceeded(ShellResult(0, "", "")))
        assertFalse(clearSucceeded(ShellResult(0, "Failed", "")))
    }

    @Test
    fun aFailedConnectionCancelsThePendingClear() {
        val m = model()
        m.onEvent(TroubleshootEvent.ClearClicked)
        m.onEvent(TroubleshootEvent.ClearConfirmed)
        SelfAdbController.fail("no service", needsNotifications = true)
        m.onAdb(SelfAdbController.state.value)
        assertFalse(m.state.value.pendingClear)
        assertEquals(R.string.install_error_notifications, m.state.value.errorRes)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun anAttemptOfAnotherPurposeIsNeitherUsedNorTaken() {
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.PhoneGrants))
        val m = model()
        m.onEvent(TroubleshootEvent.PairAdb)
        assertEquals(R.string.troubleshoot_adb_busy, m.state.value.errorRes)
        assertEquals(0, started)

        m.onEvent(TroubleshootEvent.ClearClicked)
        m.onEvent(TroubleshootEvent.ClearConfirmed)
        assertEquals(R.string.troubleshoot_adb_busy, m.state.value.errorRes)
        assertFalse(m.state.value.pendingClear)

        m.connect()
        assertNull(m.state.value.adb)
        assertTrue(ran.isEmpty())
        assertEquals(SelfAdbPurpose.PhoneGrants, SelfAdbController.state.value.purpose)
        m.onCleared() // not ours: left alone
        assertTrue(SelfAdbController.state.value.active)
    }

    @Test
    fun leavingTheScreenEndsOnlyItsOwnAttempt() {
        val m = model()
        m.onEvent(TroubleshootEvent.PairAdb)
        assertTrue(SelfAdbController.state.value.active)
        m.onCleared()
        assertFalse(SelfAdbController.state.value.active)
    }
}

class SelfAdbPurposeTest {
    private val client = fakeClient()

    @Before @After fun reset() = SelfAdbController.finish()

    @Test
    fun theClientGoesOnlyToTheOwnerOfTheAttempt() {
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.Troubleshoot))
        assertEquals(SelfAdbPurpose.Troubleshoot, SelfAdbController.state.value.purpose)
        SelfAdbController.onSession(SelfAdbState.Connected(client))
        assertNull(SelfAdbController.takeClient(SelfAdbPurpose.PhoneGrants))
        assertNotNull(SelfAdbController.takeClient(SelfAdbPurpose.Troubleshoot))
        assertNull(SelfAdbController.takeClient(SelfAdbPurpose.Troubleshoot))
    }

    @Test
    fun anotherPurposeCannotBeginWhileOneIsActiveButCanAfterAFailure() {
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.PhoneGrants))
        assertFalse(SelfAdbController.begin(SelfAdbPurpose.Troubleshoot))
        SelfAdbController.fail("x")
        assertEquals(SelfAdbPurpose.PhoneGrants, SelfAdbController.state.value.purpose) // the failure stays attributed
        assertEquals(SelfAdbPhase.Failed, SelfAdbController.state.value.phase)
        assertTrue(SelfAdbController.begin(SelfAdbPurpose.Troubleshoot))
    }

    @Test
    fun finishIfOwnedLeavesOtherAttemptsAndIdleAlone() {
        SelfAdbController.begin(SelfAdbPurpose.PhoneGrants)
        SelfAdbController.finishIfOwned(SelfAdbPurpose.Troubleshoot)
        assertTrue(SelfAdbController.state.value.active)
        SelfAdbController.finishIfOwned(SelfAdbPurpose.PhoneGrants)
        assertFalse(SelfAdbController.state.value.active)
    }
}
