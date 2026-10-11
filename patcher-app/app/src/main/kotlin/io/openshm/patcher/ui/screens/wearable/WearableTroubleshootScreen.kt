package io.openshm.patcher.ui.screens.wearable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.SelfAdbProgress

private val fixSteps = listOf(
    R.string.troubleshoot_fix_1,
    R.string.troubleshoot_fix_2,
    R.string.troubleshoot_fix_3,
    R.string.troubleshoot_fix_4,
)

private val SPLIT_MIN_WIDTH = 600.dp

/**
 * "Trouble pairing?": quick fixes, then the reset of Google Play services, by hand or through this
 * phone's own ADB. Clearing the data always goes through a warning dialog first.
 */
@Composable
fun WearableTroubleshootScreen(
    state: TroubleshootUiState,
    onEvent: (TroubleshootEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScreenScaffold(
        step = SetupStep.WearablePairing,
        title = stringResource(R.string.troubleshoot_title),
        onBack = onBack,
        modifier = modifier,
        footer = { PrimaryAction(stringResource(R.string.troubleshoot_back_to_check), onBack) },
    ) {
        Text(
            stringResource(R.string.troubleshoot_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < SPLIT_MIN_WIDTH) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberedStepsCard(R.string.troubleshoot_fixes_title, fixSteps)
                    PlayServicesSection(state, onEvent)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) { NumberedStepsCard(R.string.troubleshoot_fixes_title, fixSteps) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { PlayServicesSection(state, onEvent) }
                }
            }
        }
    }
    if (state.confirming) ConfirmClearDialog(onCancel = { onEvent(TroubleshootEvent.ClearDismissed) }, onConfirm = { onEvent(TroubleshootEvent.ClearConfirmed) })
}

@Composable
private fun ColumnScope.PlayServicesSection(state: TroubleshootUiState, onEvent: (TroubleshootEvent) -> Unit) {
    SectionTitle(stringResource(R.string.troubleshoot_gms_title))
    Text(stringResource(R.string.troubleshoot_gms_intro), style = MaterialTheme.typography.bodyMedium)
    NoteRow(Icons.Rounded.Warning, stringResource(R.string.troubleshoot_gms_warning))

    GroupedItem(0, 2) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.troubleshoot_manual_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.troubleshoot_manual_steps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedAction(
                stringResource(R.string.troubleshoot_manual_open),
                { onEvent(TroubleshootEvent.OpenPlayServicesSettings) },
                Icons.Rounded.OpenInNew,
            )
        }
    }
    GroupedItem(1, 2) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.troubleshoot_auto_title), style = MaterialTheme.typography.titleSmall)
            AutomaticPath(state, onEvent)
        }
    }
}

@Composable
private fun AutomaticPath(state: TroubleshootUiState, onEvent: (TroubleshootEvent) -> Unit) {
    val running = state.outcome == ClearOutcome.Running
    Text(
        stringResource(R.string.troubleshoot_auto_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val phase = state.adb
    if (phase != null && phase != SelfAdbPhase.Granting) {
        SelfAdbProgress(
            phase,
            onSubmitCode = { onEvent(TroubleshootEvent.SubmitPairingCode(it)) },
            onRetry = { onEvent(TroubleshootEvent.PairAdb) },
            onCancel = { onEvent(TroubleshootEvent.CancelAdb) },
            retryLabel = R.string.troubleshoot_adb_retry,
        )
    } else if (!running) {
        OutlinedAction(stringResource(R.string.troubleshoot_adb_pair), { onEvent(TroubleshootEvent.PairAdb) }, Icons.Rounded.Wifi)
    }
    if (state.pendingClear) {
        Text(stringResource(R.string.troubleshoot_clear_waiting), style = MaterialTheme.typography.bodyMedium)
    }
    if (!running && !state.pendingClear) {
        DestructiveAction(stringResource(R.string.troubleshoot_clear_action)) { onEvent(TroubleshootEvent.ClearClicked) }
    }
    Outcome(state)
}

@Composable
private fun Outcome(state: TroubleshootUiState) {
    when (state.outcome) {
        ClearOutcome.Running -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            Text(stringResource(R.string.troubleshoot_clear_running), style = MaterialTheme.typography.bodyMedium)
        }
        ClearOutcome.Success -> NoteRow(Icons.Rounded.Check, stringResource(R.string.troubleshoot_clear_done))
        ClearOutcome.PairedOnly -> NoteRow(Icons.Rounded.Check, stringResource(R.string.troubleshoot_adb_paired))
        ClearOutcome.Failed, null -> Unit
    }
    // A failed attempt already says it could not connect through its phase; a specific reason is still shown.
    if (state.adb != SelfAdbPhase.Failed || state.errorRes != R.string.phone_adb_failed) {
        state.errorRes?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
    state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4) }
}

/** Error-colored full-width button for an action that cannot be undone. */
@Composable
private fun DestructiveAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The warning that always comes before anything is cleared. Cancel is the default; confirming is the error-colored one. */
@Composable
internal fun ConfirmClearDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.troubleshoot_dialog_title)) },
        text = { Text(stringResource(R.string.troubleshoot_dialog_body)) },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.troubleshoot_dialog_confirm))
            }
        },
    )
}
