package io.openshm.patcher.ui.screens.devmode

import io.openshm.patcher.ui.components.InstructionText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

/** Whether the Health Platform developer mode has been seen on the watch. */
data class DevModeUiState(val detected: Boolean = false, val checking: Boolean = false)

sealed interface DevModeEvent {
    data object OpenHealthPlatformSettings : DevModeEvent
    data object Check : DevModeEvent
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
    StepScreenScaffold(
        step = SetupStep.WatchInstall,
        title = stringResource(R.string.devmode_title),
        onBack = onBack,
        modifier = modifier,
        secondary = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(if (state.detected) CheckStatus.Passed else if (state.checking) CheckStatus.Running else CheckStatus.Pending)
                Text(
                    stringResource(if (state.detected) R.string.devmode_detected else R.string.devmode_waiting),
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
                PrimaryAction(stringResource(R.string.action_continue), onContinue, accent = Accent.Tertiary)
            } else {
                PrimaryAction(
                    stringResource(R.string.devmode_check), { onEvent(DevModeEvent.Check) },
                    enabled = !state.checking, accent = Accent.Tertiary,
                )
                QuietAction(stringResource(R.string.devmode_skip), onContinue, accent = Accent.Tertiary)
            }
        },
    ) {
        Text(
            stringResource(R.string.devmode_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}
