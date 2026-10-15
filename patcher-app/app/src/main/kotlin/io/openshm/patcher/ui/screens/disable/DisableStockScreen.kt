package io.openshm.patcher.ui.screens.disable

import io.openshm.patcher.ui.components.ActionRow
import androidx.annotation.StringRes
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
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.LocalTwoPane
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PhoneWatchIllustration
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold

/** [AlreadyOff] and [NotInstalled] can be found before the user acts, when the screen checks the watch. */
enum class StockOutcome { None, Running, Disabled, AlreadyOff, NotInstalled, Failed }

data class StockAppChoice(val app: StockApp, val selected: Boolean, val outcome: StockOutcome = StockOutcome.None)

data class DisableStockUiState(
    val choices: List<StockAppChoice> = emptyList(),
    val running: Boolean = false,
    /** Every selected app is off or was not installed; the host moves on. */
    val done: Boolean = false,
    @StringRes val errorRes: Int? = null,
) {
    val selectedCount: Int get() = choices.count { it.selected }

    /** Every app is already off or absent: the step has nothing left to do. */
    val nothingLeft: Boolean
        get() = choices.isNotEmpty() && choices.all { it.outcome in FINISHED }

    private companion object {
        val FINISHED = setOf(StockOutcome.Disabled, StockOutcome.AlreadyOff, StockOutcome.NotInstalled)
    }
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
        secondary = { if (LocalTwoPane.current) HeroCard() },
        footer = {
            if (state.nothingLeft) {
                PrimaryAction(stringResource(R.string.action_continue), onSkip, accent = Accent.Tertiary, auto = true)
            } else {
                ActionRow {
                    PrimaryAction(
                        pluralStringResource(R.plurals.disable_action, state.selectedCount, state.selectedCount),
                        { onEvent(DisableStockEvent.Disable) },
                        enabled = state.selectedCount > 0 && !state.running,
                        accent = Accent.Tertiary,
                        auto = true,
                    )
                    QuietAction(stringResource(R.string.action_skip), onSkip, accent = Accent.Tertiary)
                }
            }
        },
    ) {
        if (!LocalTwoPane.current) HeroCard()
        Text(
            stringResource(R.string.disable_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.errorRes?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Failed, size = 24.dp)
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(verticalArrangement = GroupedSpacing) {
            state.choices.forEachIndexed { index, choice ->
                val settled = choice.outcome == StockOutcome.AlreadyOff || choice.outcome == StockOutcome.NotInstalled
                GroupedItem(
                    index, state.choices.size,
                    modifier = Modifier.toggleable(
                        value = choice.selected,
                        enabled = !settled,
                        role = Role.Checkbox,
                        onValueChange = { onEvent(DisableStockEvent.Toggle(choice.app.packageName)) },
                    ),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(choice.selected, onCheckedChange = null, enabled = !settled)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(choice.app.label, style = MaterialTheme.typography.titleSmall)
                            PackageId(choice.app.packageName)
                            Text(
                                choice.app.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (choice.outcome) {
                            StockOutcome.Running -> StatusShape(CheckStatus.Running, size = 22.dp)
                            StockOutcome.Disabled -> StatusShape(CheckStatus.Passed, size = 22.dp)
                            StockOutcome.Failed -> StatusShape(CheckStatus.Failed, size = 22.dp)
                            StockOutcome.AlreadyOff -> InfoChip(stringResource(R.string.disable_chip_already_off))
                            StockOutcome.NotInstalled -> InfoChip(stringResource(R.string.disable_chip_not_installed))
                            StockOutcome.None -> if (choice.app.optional) InfoChip(stringResource(R.string.chip_optional))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard() {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhoneWatchIllustration(scale = if (LocalTwoPane.current) 0.65f else 0.8f, linkColor = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InfoChip(
                    stringResource(R.string.disable_chip_installed),
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                )
                InfoChip(stringResource(R.string.disable_chip_wear), contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}
