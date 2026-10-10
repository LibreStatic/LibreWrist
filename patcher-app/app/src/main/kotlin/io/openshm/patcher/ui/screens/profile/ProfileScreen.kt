package io.openshm.patcher.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StepScreenScaffold

data class ProfileUiState(
    val selected: WatchProfile = WatchProfile.WearOs5,
    val options: List<WatchProfile> = WatchProfile.entries,
)

sealed interface ProfileEvent {
    data class Select(val profile: WatchProfile) : ProfileEvent
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, onClick = null)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(option.titleRes()), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(option.summaryRes()), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.padding(top = 4.dp)) { InfoChip(stringResource(option.appsRes())) }
                        }
                    }
                }
            }
        }
    }
}

private fun WatchProfile.titleRes() = when (this) {
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
