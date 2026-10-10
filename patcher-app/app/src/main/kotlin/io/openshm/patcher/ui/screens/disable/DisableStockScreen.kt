package io.openshm.patcher.ui.screens.disable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.StockApp
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PhoneWatchIllustration
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StepScreenScaffold

data class StockAppChoice(val app: StockApp, val selected: Boolean)

data class DisableStockUiState(
    val choices: List<StockAppChoice> = emptyList(),
    val running: Boolean = false,
) {
    val selectedCount: Int get() = choices.count { it.selected }
}

sealed interface DisableStockEvent {
    data class Toggle(val packageName: String) : DisableStockEvent
    data object Disable : DisableStockEvent
}

@Composable
fun DisableStockScreen(
    state: DisableStockUiState,
    onEvent: (DisableStockEvent) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScreenScaffold(
        step = SetupStep.WatchInstall,
        title = stringResource(R.string.disable_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            PrimaryAction(
                pluralStringResource(R.plurals.disable_action, state.selectedCount, state.selectedCount),
                { onEvent(DisableStockEvent.Disable) },
                enabled = state.selectedCount > 0 && !state.running,
                accent = Accent.Tertiary,
            )
            QuietAction(stringResource(R.string.action_skip), onSkip, accent = Accent.Tertiary)
        },
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneWatchIllustration(scale = 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(
                        stringResource(R.string.disable_chip_installed),
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    )
                    InfoChip(stringResource(R.string.disable_chip_wear))
                }
            }
        }
        Text(
            stringResource(R.string.disable_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = GroupedSpacing) {
            state.choices.forEachIndexed { index, choice ->
                GroupedItem(
                    index, state.choices.size,
                    modifier = Modifier.toggleable(
                        value = choice.selected,
                        role = Role.Checkbox,
                        onValueChange = { onEvent(DisableStockEvent.Toggle(choice.app.packageName)) },
                    ),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(choice.selected, onCheckedChange = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(choice.app.label, style = MaterialTheme.typography.titleSmall)
                            PackageId(choice.app.packageName)
                            Text(
                                choice.app.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (choice.app.optional) InfoChip(stringResource(R.string.chip_optional))
                    }
                }
            }
        }
    }
}
