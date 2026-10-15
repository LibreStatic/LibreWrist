package io.openshm.patcher.ui.screens.phoneadb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.InstructionText
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.screens.install.SelfAdbGuide
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import io.openshm.patcher.ui.screens.install.SelfAdbProgress

/**
 * "Connect this phone": the unattended-mode step that pairs this phone's own Wireless debugging. It
 * has no Continue and no Skip: it moves on by itself once connected (a paired phone needs no code).
 */
@Composable
fun PhoneAdbScreen(
    state: PhoneAdbUiState,
    onConnect: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenWirelessDebugging: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = state.phase
    // While the code is asked for, the field comes first and the guide stays below it.
    val askingForCode = phase == SelfAdbPhase.WaitingForCode || phase == SelfAdbPhase.WrongCode
    val guided = askingForCode || phase == SelfAdbPhase.Reconnecting || phase == SelfAdbPhase.WaitingForService
    StepScreenScaffold(
        step = SetupStep.InstallPhone,
        title = stringResource(R.string.phoneadb_title),
        onBack = onBack,
        modifier = modifier,
        numbered = false,
        footer = { FooterHint(stringResource(R.string.phoneadb_footer_hint)) },
    ) {
        InstructionText(
            stringResource(R.string.phoneadb_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (phase == null) {
                SelfAdbGuide()
                OutlinedAction(stringResource(R.string.phoneadb_connect), onConnect, Icons.Rounded.Wifi)
                QuietAction(stringResource(R.string.selfadb_open_wireless), onOpenWirelessDebugging)
            } else {
                if (guided && !askingForCode) SelfAdbGuide()
                if (phase == SelfAdbPhase.Failed && state.needsNotifications) {
                    InstructionText(stringResource(R.string.install_error_notifications), style = MaterialTheme.typography.bodyMedium)
                }
                SelfAdbProgress(
                    phase,
                    onSubmitCode = onSubmitCode,
                    // Failed offers a retry (never an automatic one) and a cancel back to the start of the step.
                    onRetry = onConnect,
                    onCancel = onCancel,
                    retryLabel = R.string.action_retry,
                )
                if (phase == SelfAdbPhase.Failed) state.error?.let { PackageId(it, maxLines = 3) }
                if (guided) {
                    if (askingForCode) SelfAdbGuide()
                    QuietAction(stringResource(R.string.selfadb_open_wireless), onOpenWirelessDebugging)
                }
            }
        }
    }
}
