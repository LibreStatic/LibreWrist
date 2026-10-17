package io.openshm.patcher.ui.screens.welcome

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.AutomationState
import io.openshm.patcher.data.Region
import io.openshm.patcher.data.chinaModeActive
import io.openshm.patcher.data.chinaModeToggleVisible

/** The explanation dialogs behind the (?) of each automation switch. */
enum class AutomationHelp(@StringRes val title: Int, @StringRes val body: Int) {
    Guide(R.string.automation_guide_title, R.string.automation_guide_help),
    Unattended(R.string.automation_unattended_title, R.string.automation_unattended_help),
    FreeRun(R.string.automation_free_run_title, R.string.automation_free_run_help),
    Uninstall(R.string.automation_uninstall_title, R.string.automation_uninstall_help),
    China(R.string.automation_china_title, R.string.automation_china_help),
}

/**
 * The automation switches under "Get started": the guide pet first, then unattended mode and its two
 * dependent switches, which are shown off and disabled while unattended mode is off, and China mode
 * where it was detected or ever set.
 */
@Composable
internal fun AutomationToggles(
    automation: AutomationState,
    onEvent: (WelcomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dependent = automation.unattended
    Column(modifier.fillMaxWidth()) {
        AutomationRow(
            label = R.string.automation_guide_title,
            checked = automation.guidePet,
            enabled = true,
            onChange = { onEvent(WelcomeEvent.SetGuidePet(it)) },
            onHelp = { onEvent(WelcomeEvent.ShowHelp(AutomationHelp.Guide)) },
        )
        AutomationRow(
            label = R.string.automation_unattended_title,
            checked = automation.unattended,
            enabled = true,
            onChange = { onEvent(WelcomeEvent.SetUnattended(it)) },
            onHelp = { onEvent(WelcomeEvent.ShowHelp(AutomationHelp.Unattended)) },
        )
        AutomationRow(
            label = R.string.automation_free_run_title,
            checked = automation.freeRun && dependent,
            enabled = dependent,
            onChange = { onEvent(WelcomeEvent.SetFreeRun(it)) },
            onHelp = { onEvent(WelcomeEvent.ShowHelp(AutomationHelp.FreeRun)) },
        )
        AutomationRow(
            label = R.string.automation_uninstall_title,
            checked = automation.uninstallMismatch && dependent,
            enabled = dependent,
            destructive = true,
            onChange = { onEvent(WelcomeEvent.SetUninstallMismatch(it)) },
            onHelp = { onEvent(WelcomeEvent.ShowHelp(AutomationHelp.Uninstall)) },
        )
        if (chinaModeToggleVisible(Region.detected, automation.chinaMode)) {
            AutomationRow(
                label = R.string.automation_china_title,
                checked = automation.chinaModeActive(Region.detected),
                enabled = true,
                onChange = { onEvent(WelcomeEvent.SetChinaMode(it)) },
                onHelp = { onEvent(WelcomeEvent.ShowHelp(AutomationHelp.China)) },
            )
        }
    }
}

@Composable
private fun AutomationRow(
    @StringRes label: Int,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    onHelp: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val tone = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.6f)
        destructive -> colors.error
        else -> colors.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = tone, modifier = Modifier.weight(1f))
        IconButton(onClick = onHelp, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.AutoMirrored.Rounded.HelpOutline,
                contentDescription = stringResource(R.string.action_help),
                tint = if (enabled) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Switch(
            checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = if (destructive) {
                SwitchDefaults.colors(
                    checkedTrackColor = colors.error,
                    checkedThumbColor = colors.onError,
                    checkedBorderColor = colors.error,
                    uncheckedBorderColor = colors.error.copy(alpha = 0.6f),
                )
            } else {
                SwitchDefaults.colors()
            },
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
    }
}

/**
 * The dialogs of the switches: the first-launch apps notice, the explanation of the (?) that is open
 * and the free-run warning.
 */
@Composable
internal fun AutomationDialogs(state: WelcomeUiState, onEvent: (WelcomeEvent) -> Unit) {
    if (state.appsNotice) {
        AlertDialog(
            onDismissRequest = { onEvent(WelcomeEvent.AcknowledgeAppsNotice) },
            title = { Text(stringResource(R.string.automation_apps_notice_title)) },
            text = {
                Text(
                    AnnotatedString.fromHtml(stringResource(R.string.automation_apps_notice_body)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { onEvent(WelcomeEvent.AcknowledgeAppsNotice) }) {
                    Text(stringResource(R.string.outcome_acknowledge))
                }
            },
        )
        return
    }
    state.help?.let { help ->
        AlertDialog(
            onDismissRequest = { onEvent(WelcomeEvent.HideHelp) },
            title = { Text(stringResource(help.title)) },
            // The bodies mark what must not be missed (lost data) with <b>, escaped in the resources.
            text = { Text(AnnotatedString.fromHtml(stringResource(help.body)), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { onEvent(WelcomeEvent.HideHelp) }) { Text(stringResource(R.string.outcome_acknowledge)) }
            },
        )
    }
    if (state.freeRunWarning) {
        var dontShowAgain by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { onEvent(WelcomeEvent.CancelFreeRun) },
            title = { Text(stringResource(R.string.automation_free_run_warning_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.automation_free_run_warning_body), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(dontShowAgain, role = Role.Checkbox, onValueChange = { dontShowAgain = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(dontShowAgain, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(stringResource(R.string.automation_dont_show_again), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onEvent(WelcomeEvent.ConfirmFreeRun(dontShowAgain)) }) {
                    Text(stringResource(R.string.automation_free_run_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(WelcomeEvent.CancelFreeRun) }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
