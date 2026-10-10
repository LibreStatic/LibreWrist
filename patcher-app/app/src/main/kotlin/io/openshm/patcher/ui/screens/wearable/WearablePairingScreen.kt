package io.openshm.patcher.ui.screens.wearable

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold

data class WearableCheck(
    val id: String,
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val status: CheckStatus,
)

/** What the Galaxy Wearable pairing check found: the watch must already be paired to this phone. */
data class WearablePairingUiState(val checks: List<WearableCheck> = emptyList()) {
    val allPassed: Boolean get() = checks.isNotEmpty() && checks.all { it.status == CheckStatus.Passed }
}

sealed interface WearablePairingEvent {
    data object Recheck : WearablePairingEvent
}

@Composable
fun WearablePairingScreen(
    state: WearablePairingUiState,
    onEvent: (WearablePairingEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScreenScaffold(
        step = SetupStep.WearablePairing,
        title = stringResource(R.string.wearable_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            PrimaryAction(stringResource(R.string.action_continue), onContinue, enabled = state.allPassed)
            QuietAction(stringResource(R.string.wearable_recheck), { onEvent(WearablePairingEvent.Recheck) })
        },
    ) {
        Text(
            stringResource(R.string.wearable_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = GroupedSpacing) {
            state.checks.forEachIndexed { index, check ->
                GroupedItem(index, state.checks.size) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusShape(check.status)
                        Column {
                            Text(stringResource(check.title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(check.detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        NoteRow(Icons.Rounded.Watch, stringResource(R.string.wearable_note))
    }
}
