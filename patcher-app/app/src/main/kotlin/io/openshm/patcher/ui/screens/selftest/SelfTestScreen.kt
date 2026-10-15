package io.openshm.patcher.ui.screens.selftest

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.ContainerTextAction
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.HapticWhen
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.components.UnattendedAttention

/** A line of text built from a string resource and its arguments, so rows can name packages and grants. */
data class SelfTestText(@StringRes val res: Int, val args: List<String> = emptyList())

@Composable
internal fun SelfTestText.asString(): String = stringResource(res, *args.toTypedArray())

/** The existing screen a failed row can send the user to. */
/** How a row gets repaired: a screen to open, or [WatchGrants], applied in place over watch ADB. */
enum class SelfTestFix { InstallPhone, InstallWatch, WatchPair, Build, DisableStock, DevMode, WearablePairing, WatchGrants }

data class SelfTestRow(
    val id: String,
    val title: SelfTestText,
    val status: CheckStatus,
    /** Why the row is not green (or a short fact when it is). */
    val detail: SelfTestText? = null,
    /** Shown on a failed or skipped row when the app knows a way to repair it. */
    val fix: SelfTestFix? = null,
    /** Set on rows only the person holding the devices can answer; the text is the yes/no question. */
    @StringRes val question: Int? = null,
) {
    val isHuman: Boolean get() = question != null
}

data class SelfTestSection(@StringRes val title: Int, val rows: List<SelfTestRow>)

/** A check only the person holding the devices can answer ("Did the watch buzz?"). */
data class HumanCheck(val index: Int, val total: Int, @StringRes val question: Int)

data class SelfTestUiState(
    val sections: List<SelfTestSection> = emptyList(),
    /** Fingerprint of what the questions depend on; null when it cannot be known (the answers are then not reusable). */
    val manualFingerprint: String? = null,
    /** True from the start of a run until it is known whether the questions are asked or answered from the last time. */
    val resolvingManual: Boolean = false,
    /** The questions were answered from the last time, not by the user in this run. */
    val manualReused: Boolean = false,
) {
    private val allRows get() = sections.flatMap { it.rows }
    val passedCount: Int get() = allRows.count { it.status == CheckStatus.Passed }
    val warningCount: Int get() = allRows.count { it.status == CheckStatus.Warning }
    val failedCount: Int get() = allRows.count { it.status == CheckStatus.Failed }
    val totalCount: Int get() = allRows.size

    /** Sections of automated checks, in order; the questions live in [manualSection]. */
    val automatedSections: List<SelfTestSection> get() = sections.filter { s -> s.rows.none { it.isHuman } }

    /** The questions only the person holding the devices can answer, if any. */
    val manualSection: SelfTestSection? get() = sections.firstOrNull { s -> s.rows.any { it.isHuman } }

    /**
     * The questions are asked only once every automated row ended without a failure (a warning does
     * not block the setup, so it does not hold them back either).
     */
    val manualOpen: Boolean
        get() = !running && !resolvingManual && manualSection != null &&
            allRows.none { !it.isHuman && it.status == CheckStatus.Failed }

    /** The first question still unanswered, with its place among all of them; null until [manualOpen]. */
    val humanCheck: HumanCheck?
        get() {
            if (!manualOpen) return null
            val questions = allRows.filter { it.isHuman }
            val next = questions.indexOfFirst { it.status == CheckStatus.Pending }
            return if (next < 0) null else HumanCheck(next + 1, questions.size, questions[next].question!!)
        }

    /** True while an automated row is waiting or running (a question left open does not count). */
    val running: Boolean
        get() = allRows.any { it.status == CheckStatus.Running || (it.status == CheckStatus.Pending && !it.isHuman) }

    /** Every row ended, or the automated rows did with a failure (the questions are then never asked). */
    val finished: Boolean
        get() = allRows.isNotEmpty() && !running && !resolvingManual &&
            (!manualOpen || allRows.none { it.status == CheckStatus.Pending })
    val allPassed: Boolean get() = finished && allRows.all { it.status == CheckStatus.Passed }

    /** Warnings (skipped or unreadable checks) do not stop the setup; a failure does. */
    val canContinue: Boolean get() = finished && failedCount == 0
}

sealed interface SelfTestEvent {
    data class Fix(val rowId: String) : SelfTestEvent
    data object Share : SelfTestEvent
    data object Rerun : SelfTestEvent
    data class Answer(val yes: Boolean) : SelfTestEvent
    data object AskAgain : SelfTestEvent
}

@Composable
fun SelfTestScreen(
    state: SelfTestUiState,
    onEvent: (SelfTestEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val split = state.splitIndex()
    // A question to answer, or a failed check to fix.
    UnattendedAttention(state.humanCheck != null || (state.finished && state.failedCount > 0))
    HapticWhen(state.allPassed, HapticFeedbackType.Confirm)
    HapticWhen(state.finished && state.failedCount > 0, HapticFeedbackType.Reject)
    StepScreenScaffold(
        step = SetupStep.SelfTest,
        title = stringResource(R.string.selftest_title),
        onBack = onBack,
        modifier = modifier,
        topTrailing = {
            IconAction(Icons.Rounded.Share, stringResource(R.string.action_share_report), { onEvent(SelfTestEvent.Share) })
        },
        footer = { PrimaryAction(stringResource(R.string.action_continue), onContinue, enabled = state.canContinue, auto = true) },
        secondary = {
            state.automatedSections.drop(split).forEach { SectionBlock(it, onEvent) }
            OutlinedAction(
                stringResource(R.string.selftest_rerun),
                { onEvent(SelfTestEvent.Rerun) },
                Icons.Rounded.Refresh,
                enabled = !state.running && !state.resolvingManual,
            )
        },
    ) {
        // On top, so the questions are not lost below the automated rows.
        val manual = state.manualSection
        if (manual != null && state.manualOpen) ManualStage(manual, state.humanCheck, state.manualReused, onEvent)
        state.automatedSections.take(split).forEach { SectionBlock(it, onEvent) }
    }
}

/** Automated sections are shared between the two panes by row count; narrow, the secondary simply follows the primary. */
private fun SelfTestUiState.splitIndex(): Int {
    val automated = automatedSections
    val total = automated.sumOf { it.rows.size }
    var seen = 0
    automated.forEachIndexed { i, s ->
        if (seen * 2 >= total) return i
        seen += s.rows.size
    }
    return automated.size
}

/** The questions: the open one first, then every question with its answer. Scrolls itself into view when it appears. */
@Composable
private fun ManualStage(section: SelfTestSection, check: HumanCheck?, reused: Boolean, onEvent: (SelfTestEvent) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { requester.bringIntoView() }
    Column(Modifier.bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        check?.let { HumanCheckCard(it, onEvent) }
        SectionBlock(section, onEvent)
        if (reused) {
            ContainerTextAction(
                stringResource(R.string.selftest_ask_again),
                { onEvent(SelfTestEvent.AskAgain) },
                onColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun SectionBlock(section: SelfTestSection, onEvent: (SelfTestEvent) -> Unit) {
    Column {
        SectionTitle(stringResource(section.title))
        Column(verticalArrangement = GroupedSpacing) {
            section.rows.forEachIndexed { index, row -> CheckRow(index, section.rows.size, row, onEvent) }
        }
    }
}

@Composable
private fun CheckRow(index: Int, count: Int, row: SelfTestRow, onEvent: (SelfTestEvent) -> Unit) {
    val failed = row.status == CheckStatus.Failed
    GroupedItem(
        index, count,
        containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusShape(row.status)
            Column(Modifier.weight(1f)) {
                Text(row.title.asString(), style = MaterialTheme.typography.bodyLarge)
                row.detail?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            }
            if (row.fix != null && (row.status == CheckStatus.Failed || row.status == CheckStatus.Warning)) {
                ContainerTextAction(
                    stringResource(R.string.action_fix),
                    { onEvent(SelfTestEvent.Fix(row.id)) },
                    onColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun HumanCheckCard(check: HumanCheck, onEvent: (SelfTestEvent) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.selftest_human_label, check.index, check.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inversePrimary,
            )
            Text(stringResource(check.question), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Button(
                    onClick = { onEvent(SelfTestEvent.Answer(true)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inversePrimary,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text(stringResource(R.string.action_yes)) }
                OutlinedButton(
                    onClick = { onEvent(SelfTestEvent.Answer(false)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                ) { Text(stringResource(R.string.action_no)) }
            }
        }
    }
}
