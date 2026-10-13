package io.openshm.patcher.ui.screens.revert

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.ActionRow
import io.openshm.patcher.ui.components.SinglePaneMaxWidth
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.ContentColumn
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.TopActions
import io.openshm.patcher.ui.components.WavyBusyProgress

/**
 * "Undo all changes": what the setup left on the phone and the watch, the confirmation, the per-item
 * progress and the result with the manual steps and the patcher's own uninstall. [onClose] leaves the
 * screen for good (Done); [onBack] is the arrow, hidden while the revert runs.
 */
@Composable
fun RevertScreen(
    state: RevertUiState,
    onEvent: (RevertEvent) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state.phase == RevertPhase.Running
    BackHandler(enabled = running) {}
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Same height while running, so the title does not jump when the back arrow goes away.
        if (running) Spacer(Modifier.padding(top = 8.dp).height(48.dp)) else TopActions(onBack = onBack, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.revert_title),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
        )
        ContentColumn(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RevertBody(state)
            }
        }
        // Not ContentColumn: that one fills the height and would leave the scrolling body none.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = SinglePaneMaxWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
                RevertFooter(state, onEvent, onClose)
            }
        }
    }
    if (state.confirming) RevertConfirmDialog(onConfirm = { onEvent(RevertEvent.Start) }, onDismiss = { onEvent(RevertEvent.DismissRevert) })
    if (state.confirmingSelfUninstall) {
        SelfUninstallDialog(state.partial, onConfirm = { onEvent(RevertEvent.ConfirmSelfUninstall) }, onDismiss = { onEvent(RevertEvent.DismissSelfUninstall) })
    }
}

@Composable
private fun RevertBody(state: RevertUiState) {
    if (state.phase == RevertPhase.Done) DoneHeader(state)
    else Text(stringResource(R.string.revert_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

    if (state.phase == RevertPhase.Probing) {
        Text(stringResource(R.string.revert_probing), style = MaterialTheme.typography.bodyMedium)
        WavyBusyProgress()
        return
    }
    if (state.watch == WatchStatus.Unreachable) WatchProblem(state, compact = state.phase == RevertPhase.Done)
    if (state.watch == WatchStatus.Skipped) NoteRow(Icons.Rounded.Info, stringResource(R.string.revert_watch_skipped))
    for (device in listOf(Device.Watch, Device.Phone)) {
        val rows = state.rows.filter { it.item.device == device }
        if (rows.isEmpty()) continue
        Column {
            SectionTitle(stringResource(if (device == Device.Phone) R.string.changes_on_phone else R.string.changes_on_watch))
            Column(verticalArrangement = GroupedSpacing) {
                rows.forEachIndexed { index, row -> RevertRowItem(row, index, rows.size) }
            }
        }
    }
    if (state.notInstalled.isNotEmpty()) {
        NoteRow(Icons.Rounded.Info, stringResource(R.string.revert_not_installed, state.notInstalled.map { it.label }.distinct().joinToString(", ")))
    }
    if (state.systemApps.isNotEmpty()) {
        NoteRow(Icons.Rounded.Info, stringResource(R.string.revert_system_apps, state.systemApps.map { it.label }.distinct().joinToString(", ")))
    }
    if (state.phase == RevertPhase.Done) ManualSteps()
}

@Composable
private fun DoneHeader(state: RevertUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusShape(if (state.complete) CheckStatus.Passed else CheckStatus.Warning, size = 40.dp)
        Text(
            stringResource(if (state.complete) R.string.revert_done_complete_title else R.string.revert_done_partial_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
    Text(
        stringResource(if (state.complete) R.string.revert_done_complete_body else R.string.revert_done_partial_body),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WatchProblem(state: RevertUiState, compact: Boolean) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Failed, size = 24.dp)
                Text(stringResource(R.string.revert_watch_unreachable_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            val reason = state.watchErrorRes ?: if (compact) R.string.watch_error_unreachable else null
            reason?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer) }
            if (!compact) Text(stringResource(R.string.revert_watch_unreachable_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun RevertRowItem(row: RevertRow, index: Int, count: Int) {
    val item = row.item
    GroupedItem(index, count) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (item.action) {
                        RevertAction.RestoreSetting -> stringResource(R.string.revert_setting_label)
                        RevertAction.RemoveExemption -> stringResource(R.string.revert_exemption_label)
                        else -> item.label
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (item.action != RevertAction.RestoreSetting && item.action != RevertAction.RemoveExemption && row.outcome is StepOutcome.Failed) PackageId(item.pkg)
                val outcome = row.outcome
                val note = when {
                    outcome != null -> outcomeText(outcome, item.action)
                    item.action == RevertAction.Enable -> stringResource(R.string.revert_enable_note)
                    item.action == RevertAction.RestoreSetting -> stringResource(R.string.revert_setting_note)
                    item.action == RevertAction.RemoveExemption -> stringResource(R.string.revert_exemption_note)
                    else -> null
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            val outcome = row.outcome
            if (outcome == null) {
                InfoChip(
                    stringResource(
                        when (item.action) {
                            RevertAction.Uninstall, RevertAction.RemoveExemption -> R.string.revert_action_remove
                            RevertAction.Enable -> R.string.revert_action_enable
                            RevertAction.RestoreSetting -> R.string.revert_action_restore
                        },
                    ),
                )
            } else {
                StatusShape(statusOf(outcome), size = 22.dp)
            }
        }
    }
}

private fun statusOf(outcome: StepOutcome) = when (outcome) {
    StepOutcome.Running -> CheckStatus.Running
    StepOutcome.Done, is StepOutcome.Skipped -> CheckStatus.Passed
    StepOutcome.Cancelled, is StepOutcome.WatchUnreachable -> CheckStatus.Warning
    is StepOutcome.Failed -> CheckStatus.Failed
}

@Composable
private fun outcomeText(outcome: StepOutcome, action: RevertAction): String = when (outcome) {
    StepOutcome.Running -> stringResource(
        when (action) {
            RevertAction.Uninstall -> R.string.revert_progress_removing
            RevertAction.Enable -> R.string.revert_progress_enabling
            RevertAction.RestoreSetting, RevertAction.RemoveExemption -> R.string.revert_progress_restoring
        },
    )
    StepOutcome.Done -> stringResource(
        when (action) {
            RevertAction.Uninstall -> R.string.revert_result_removed
            RevertAction.Enable -> R.string.revert_result_enabled
            RevertAction.RestoreSetting, RevertAction.RemoveExemption -> R.string.revert_result_restored
        },
    )
    is StepOutcome.Skipped -> stringResource(
        when (outcome.reason) {
            SkipReason.NotInstalled -> R.string.revert_result_gone
            SkipReason.SystemApp -> R.string.revert_result_system
            SkipReason.NothingToDo -> R.string.revert_result_unchanged
        },
    )
    StepOutcome.Cancelled -> stringResource(R.string.revert_result_cancelled)
    is StepOutcome.Failed -> stringResource(R.string.revert_result_failed, outcome.message)
    is StepOutcome.WatchUnreachable -> stringResource(R.string.revert_result_watch)
}

@Composable
private fun ManualSteps() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.revert_manual_title))
        NoteRow(Icons.Rounded.Info, stringResource(R.string.revert_manual_devmode))
        NoteRow(Icons.Rounded.Wifi, stringResource(R.string.revert_manual_adb))
        NoteRow(Icons.Rounded.Key, stringResource(R.string.revert_manual_keys))
    }
}

@Composable
private fun RevertFooter(state: RevertUiState, onEvent: (RevertEvent) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            RevertPhase.Probing -> Unit
            RevertPhase.Review -> if (state.watchNeedsDecision) {
                ActionRow {
                    PrimaryAction(stringResource(R.string.revert_watch_retry), { onEvent(RevertEvent.Reprobe) })
                    QuietAction(stringResource(R.string.revert_watch_skip), { onEvent(RevertEvent.SkipWatch) })
                }
            } else {
                PrimaryAction(stringResource(R.string.revert_start), { onEvent(RevertEvent.AskRevert) }, enabled = state.canRevert, accent = Accent.Error)
            }
            RevertPhase.Running -> PrimaryAction(stringResource(R.string.revert_running), {}, enabled = false)
            RevertPhase.Done -> {
                state.selfUninstall?.let {
                    Text(
                        when (it) {
                            SelfUninstallResult.Cancelled -> stringResource(R.string.revert_self_cancelled)
                            is SelfUninstallResult.Failed -> stringResource(R.string.revert_self_failed, it.message)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it is SelfUninstallResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionRow {
                    PrimaryAction(stringResource(R.string.action_done), onClose)
                    OutlinedAction(stringResource(R.string.revert_self_action), { onEvent(RevertEvent.AskSelfUninstall) }, Icons.Rounded.Delete, tint = MaterialTheme.colorScheme.error)
                }
                if (state.partial) QuietAction(stringResource(R.string.revert_check_again), { onEvent(RevertEvent.Reprobe) })
            }
        }
    }
}

/** Asks before anything is removed: the apps lose their data and this cannot be undone. */
@Composable
internal fun RevertConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.revert_confirm_title)) },
        text = { Text(stringResource(R.string.revert_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.revert_confirm_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The last, explicit step: removes this app. [partial] warns that part of the revert is still open. */
@Composable
internal fun SelfUninstallDialog(partial: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.revert_self_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.revert_self_body))
                if (partial) Text(stringResource(R.string.revert_self_body_partial), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.revert_self_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
