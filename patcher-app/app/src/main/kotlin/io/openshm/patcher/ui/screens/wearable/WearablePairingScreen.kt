package io.openshm.patcher.ui.screens.wearable

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
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
    val action: WearableCheckAction? = null,
)

/** What the Galaxy Wearable pairing checks found: the watch must already be paired to this phone. */
data class WearablePairingUiState(
    val checks: List<WearableCheck> = emptyList(),
    /** Decides between "Open Galaxy Wearable" and "Get Galaxy Wearable". */
    val galaxyWearableInstalled: Boolean = false,
) {
    val allPassed: Boolean get() = checks.isNotEmpty() && checks.all { it.status == CheckStatus.Passed }
}

sealed interface WearablePairingEvent {
    data object Recheck : WearablePairingEvent
    data object AllowBluetooth : WearablePairingEvent
    data object OpenGalaxyWearable : WearablePairingEvent
    data object GetGalaxyWearable : WearablePairingEvent

    /** Opens the "Trouble pairing?" guide. */
    data object Troubleshoot : WearablePairingEvent
}

private val howToSteps = listOf(
    R.string.wearable_howto_1,
    R.string.wearable_howto_2,
    R.string.wearable_howto_3,
    R.string.wearable_howto_4,
    R.string.wearable_howto_5,
)

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
            stringResource(R.string.wearable_intro_galaxy),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HowToPair()
        FilledTonalButton(
            onClick = {
                onEvent(if (state.galaxyWearableInstalled) WearablePairingEvent.OpenGalaxyWearable else WearablePairingEvent.GetGalaxyWearable)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.galaxyWearableInstalled) R.string.wearable_open_app else R.string.wearable_get_app))
        }
        Column(verticalArrangement = GroupedSpacing) {
            state.checks.forEachIndexed { index, check ->
                GroupedItem(index, state.checks.size) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusShape(check.status)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(check.title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(check.detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (check.action == WearableCheckAction.AllowBluetooth) {
                            TextButton(onClick = { onEvent(WearablePairingEvent.AllowBluetooth) }) {
                                Text(stringResource(R.string.wearable_allow))
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { onEvent(WearablePairingEvent.Troubleshoot) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.troubleshoot_open))
        }
        NoteRow(Icons.Rounded.Watch, stringResource(R.string.wearable_note_back))
    }
}

/** The numbered walk-through of pairing a watch in Galaxy Wearable. */
@Composable
private fun HowToPair() = NumberedStepsCard(R.string.wearable_howto_title, howToSteps)

/** A card with a heading and a numbered list of short steps. */
@Composable
internal fun NumberedStepsCard(@StringRes title: Int, steps: List<Int>) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.semantics { heading() },
            )
            steps.forEachIndexed { index, res ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(24.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiary, textAlign = TextAlign.Center)
                    }
                    Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
    }
}
