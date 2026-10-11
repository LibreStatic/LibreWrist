package io.openshm.patcher.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.device.DetectedWatch
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StepScreenScaffold

/** Outcome of the "Check paired watches" action. */
enum class PairedCheck { Idle, Done, NoWatch, Denied }

data class ProfileUiState(
    val selected: WatchProfile = WatchProfile.WearOs5,
    val options: List<WatchProfile> = WatchProfile.entries,
    /** What the watch itself reported over ADB, when it could be reached. */
    val detected: DetectedWatch? = null,
    /** What a paired Bluetooth device name suggests. */
    val hint: WatchModelHint? = null,
    val pairedCheck: PairedCheck = PairedCheck.Idle,
)

sealed interface ProfileEvent {
    data class Select(val profile: WatchProfile) : ProfileEvent
    data object CheckPairedWatches : ProfileEvent
}

@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onEvent: (ProfileEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScreenScaffold(
        step = SetupStep.Profile,
        title = stringResource(R.string.profile_title),
        onBack = onBack,
        modifier = modifier,
        footer = { PrimaryAction(stringResource(R.string.action_continue), onContinue) },
    ) {
        Text(
            stringResource(R.string.profile_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = GroupedSpacing) {
            state.options.forEachIndexed { index, option ->
                val selected = option == state.selected
                GroupedItem(
                    index,
                    state.options.size,
                    modifier = Modifier.selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onEvent(ProfileEvent.Select(option)) },
                    ),
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    val onContainer = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = onContainer, unselectedColor = onContainer),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(option.titleRes()), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(option.summaryRes()), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.padding(top = 4.dp)) { InfoChip(stringResource(option.appsRes()), contentColor = onContainer) }
                        }
                    }
                }
            }
        }
        state.detected?.let { DetectedCard(it) }
        PairedWatchesSection(state, onEvent)
        HowToTell()
    }
}

@Composable
private fun DetectedCard(detected: DetectedWatch) {
    val profile = stringResource(detected.profile.titleRes())
    GroupedItem(0, 1, containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Text(
            stringResource(R.string.profile_detected, detected.versionLabel, profile),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun PairedWatchesSection(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedAction(
            stringResource(R.string.profile_check_paired),
            { onEvent(ProfileEvent.CheckPairedWatches) },
            Icons.Rounded.Bluetooth,
        )
        val message = when (val hint = state.hint) {
            is WatchModelHint.Certain -> stringResource(R.string.profile_hint_certain, hint.name)
            is WatchModelHint.Depends -> stringResource(R.string.profile_hint_depends, hint.name)
            null -> when (state.pairedCheck) {
                PairedCheck.Denied -> stringResource(R.string.profile_check_denied)
                PairedCheck.NoWatch -> stringResource(R.string.profile_check_none)
                PairedCheck.Idle, PairedCheck.Done -> null
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Opens the illustrated walk-through to the watch's software version. */
@Composable
private fun HowToTell() {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedAction(stringResource(R.string.profile_howto_button), { open = true }, Icons.Rounded.HelpOutline)
    if (open) WatchVersionGuideDialog(onDismiss = { open = false })
}

internal fun WatchProfile.titleRes() = when (this) {
    WatchProfile.WearOs5 -> R.string.profile_wear5_title
    WatchProfile.WearOs3Or4 -> R.string.profile_wear3_title
}

private fun WatchProfile.summaryRes() = when (this) {
    WatchProfile.WearOs5 -> R.string.profile_wear5_summary
    WatchProfile.WearOs3Or4 -> R.string.profile_wear3_summary
}

private fun WatchProfile.appsRes() = when (this) {
    WatchProfile.WearOs5 -> R.string.profile_wear5_apps
    WatchProfile.WearOs3Or4 -> R.string.profile_wear3_apps
}
