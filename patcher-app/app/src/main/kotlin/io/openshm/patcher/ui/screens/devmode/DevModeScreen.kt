package io.openshm.patcher.ui.screens.devmode

import io.openshm.patcher.ui.components.ActionRow
import io.openshm.patcher.ui.components.InstructionText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.openshm.patcher.data.Automation
import io.openshm.patcher.ui.components.AutoTextButton
import io.openshm.patcher.ui.components.UnattendedAttention
import io.openshm.patcher.ui.components.observeUserInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.components.SuccessOverlay

/** Whether the Health Platform developer mode has been seen on the watch, and the watch's state. */
data class DevModeUiState(
    val detected: Boolean = false,
    val checking: Boolean = false,
    /** The background watch is running. */
    val watching: Boolean = false,
    /** The last watch poll reached the watch. */
    val watchReachable: Boolean = true,
    val notice: DevModeNotice? = null,
    /** Show the success overlay once, then move on. */
    val celebrate: Boolean = false,
)

/** A short dialog answering a button press; [NotYet] means turning it on for the user failed. */
enum class DevModeNotice { LookAtWatch, NotYet, Unreachable }

sealed interface DevModeEvent {
    data object OpenHealthPlatformSettings : DevModeEvent
    data object Enable : DevModeEvent
    data object DismissNotice : DevModeEvent
    data object Celebrated : DevModeEvent
}

private val steps = listOf(
    R.string.devmode_step_settings,
    R.string.devmode_step_apps,
    R.string.devmode_step_health_platform,
    R.string.devmode_step_tap,
)

@Composable
fun DevModeScreen(
    state: DevModeUiState,
    onEvent: (DevModeEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Turning it on failed, or the watch stopped answering: the user has to step in.
    UnattendedAttention(
        state.notice == DevModeNotice.NotYet || state.notice == DevModeNotice.Unreachable ||
            (!state.detected && !state.watchReachable),
    )
    StepScreenScaffold(
        step = SetupStep.WatchInstall,
        title = stringResource(R.string.devmode_title),
        onBack = onBack,
        modifier = modifier,
        secondary = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(
                    when {
                        state.detected -> CheckStatus.Passed
                        !state.watchReachable -> CheckStatus.Warning
                        state.checking || state.watching -> CheckStatus.Running
                        else -> CheckStatus.Pending
                    },
                )
                Text(
                    stringResource(
                        when {
                            state.detected -> R.string.devmode_detected
                            !state.watchReachable -> R.string.devmode_watch_unreachable
                            else -> R.string.devmode_waiting
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            QuietAction(
                stringResource(R.string.devmode_open_settings),
                { onEvent(DevModeEvent.OpenHealthPlatformSettings) },
                accent = Accent.Tertiary,
            )
        },
        footer = {
            if (state.detected) {
                PrimaryAction(stringResource(R.string.action_continue), onContinue, accent = Accent.Tertiary, auto = true)
            } else {
                ActionRow {
                    PrimaryAction(
                        stringResource(if (state.checking) R.string.devmode_enabling else R.string.devmode_enable),
                        { onEvent(DevModeEvent.Enable) },
                        enabled = !state.checking, accent = Accent.Tertiary, auto = true,
                    )
                    QuietAction(stringResource(R.string.devmode_skip), onContinue, accent = Accent.Tertiary)
                }
            }
        },
    ) {
        Text(
            stringResource(R.string.devmode_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.devmode_manual_header),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        Column(verticalArrangement = GroupedSpacing) {
            steps.forEachIndexed { index, res ->
                GroupedItem(index, steps.size) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(32.dp).background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        InstructionText(stringResource(res), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
    state.notice?.let { DevModeNoticeDialog(it) { onEvent(DevModeEvent.DismissNotice) } }
    if (state.celebrate) {
        SuccessOverlay(
            title = stringResource(R.string.devmode_success_title),
            message = stringResource(R.string.devmode_success_message),
            onFinished = {
                onEvent(DevModeEvent.Celebrated)
                onContinue()
            },
        )
    }
}

@Composable
private fun DevModeNoticeDialog(notice: DevModeNotice, onDismiss: () -> Unit) {
    val (icon, title, text) = when (notice) {
        DevModeNotice.LookAtWatch -> Triple(Icons.Rounded.Watch, R.string.devmode_look_title, R.string.devmode_look_message)
        DevModeNotice.NotYet -> Triple(Icons.Rounded.TouchApp, R.string.devmode_not_yet_title, R.string.devmode_not_yet_message)
        DevModeNotice.Unreachable -> Triple(Icons.Rounded.WifiOff, R.string.devmode_unreachable_title, R.string.devmode_unreachable_message)
    }
    val freeRunActive = Automation.state.collectAsStateWithLifecycle().value.freeRunActive
    AlertDialog(
        onDismissRequest = onDismiss,
        // The dialog is its own window: it reports touches itself so they pause the countdown.
        modifier = Modifier.observeUserInput(),
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(stringResource(title), textAlign = TextAlign.Center) },
        text = { Text(stringResource(text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            val label = stringResource(R.string.outcome_acknowledge)
            if (notice == DevModeNotice.LookAtWatch) {
                AutoTextButton(label, onDismiss, auto = freeRunActive)
            } else {
                TextButton(onClick = onDismiss) { Text(label) }
            }
        },
    )
}
