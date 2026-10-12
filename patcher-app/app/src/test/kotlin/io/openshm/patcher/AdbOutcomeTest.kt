package io.openshm.patcher

import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.screens.install.AdbOutcome
import io.openshm.patcher.ui.screens.install.GrantRow
import io.openshm.patcher.ui.screens.install.InstallItem
import io.openshm.patcher.ui.screens.install.adbOutcomeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbOutcomeTest {
    private fun row(name: String, status: CheckStatus, optional: Boolean = false) =
        GrantRow(Grant(GrantKind.Permission, name, optional), status)

    private fun item(status: CheckStatus = CheckStatus.Passed, vararg grants: GrantRow) =
        InstallItem("out", "App", "io.example", status, grants.toList())

    @Test
    fun `a failed run fails with the error detail`() {
        val outcome = adbOutcomeOf(false, listOf(item(CheckStatus.Passed, row("A", CheckStatus.Passed))), "boom")
        assertEquals(AdbOutcome.Failure("boom"), outcome)
    }

    @Test
    fun `a run with every required grant done succeeds`() {
        val items = listOf(item(CheckStatus.Passed, row("A", CheckStatus.Passed), row("B", CheckStatus.Warning)))
        assertEquals(AdbOutcome.Success, adbOutcomeOf(true, items, null))
    }

    @Test
    fun `a required grant still pending fails and is named`() {
        val items = listOf(item(CheckStatus.Passed, row("A", CheckStatus.Passed), row("ANSWER_PHONE_CALLS", CheckStatus.Pending)))
        val outcome = adbOutcomeOf(true, items, null)
        assertTrue(outcome is AdbOutcome.Failure)
        assertTrue((outcome as AdbOutcome.Failure).detail!!.contains("ANSWER_PHONE_CALLS"))
        assertTrue(!outcome.detail!!.contains("A,"))
    }

    @Test
    fun `an optional grant left open does not fail`() {
        val items = listOf(item(CheckStatus.Passed, row("A", CheckStatus.Passed), row("OPT", CheckStatus.Pending, optional = true)))
        assertEquals(AdbOutcome.Success, adbOutcomeOf(true, items, null))
    }

    @Test
    fun `an item that is not installed is ignored`() {
        val items = listOf(item(CheckStatus.Failed, row("A", CheckStatus.Pending)))
        assertEquals(AdbOutcome.Success, adbOutcomeOf(true, items, null))
    }
}
