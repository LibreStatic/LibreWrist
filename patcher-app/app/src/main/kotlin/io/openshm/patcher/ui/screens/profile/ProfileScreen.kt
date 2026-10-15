package io.openshm.patcher.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    /** What the watch itself reported (over ADB, or in its Wi-Fi announcement), when it could be reached. */
    val detected: DetectedWatch? = null,
    /** What a paired Bluetooth device name suggests. */
    val hint: WatchModelHint? = null,
    val pairedCheck: PairedCheck = PairedCheck.Idle,
    /** Listening for the watch's Wi-Fi announcement and nothing is detected yet. */
    val scanning: Boolean = false,
)

sealed interface ProfileEvent {
    data class Select(val profile: WatchProfile) : ProfileEvent
    data object CheckPairedWatches : ProfileEvent

    /** The "Allow" action of the denied card: ask again, or open the app settings when asking is no longer possible. */
    data object AllowBluetooth : ProfileEvent
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
        footer = { PrimaryAction(stringResource(R.string.action_continue), onContinue, auto = true) },
        secondary = {
            state.detected?.let { DetectedCard(it) }
            PairedWatchesSection(state, onEvent)
            if (state.scanning && state.detected == null) ScanningRow()
            HowToTell()
        },
    ) {
        Text(
            stringResource(R.string.profile_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val haptic = LocalHapticFeedback.current
        Column(verticalArrangement = GroupedSpacing) {
            state.options.forEachIndexed { index, option ->
                val selected = option == state.selected
                GroupedItem(
                    index,
                    state.options.size,
                    modifier = Modifier.selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = {
                            if (!selected) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onEvent(ProfileEvent.Select(option))
                        },
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
    }
}

@Composable
private fun DetectedCard(detected: DetectedWatch) {
    val profile = stringResource(detected.profile.titleRes())
    val colors = MaterialTheme.colorScheme
    val body = when (detected.source) {
        DetectedWatch.Source.Adb -> stringResource(R.string.profile_detected, detected.versionLabel, profile)
        DetectedWatch.Source.Network ->
            stringResource(R.string.profile_detected_network, detected.model.orEmpty(), detected.sdk.toString(), profile)
    }
    WatchStatusCard(Icons.Rounded.Verified, null, body, colors.tertiaryContainer, colors.onTertiaryContainer)
}

/** What the Bluetooth check found (or why it could not look), with the retry that fits it. */
@Composable
private fun PairedWatchesSection(state: ProfileUiState, onEvent: (ProfileEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    when (val hint = state.hint) {
        is WatchModelHint.Certain -> WatchStatusCard(
            Icons.Rounded.Watch, hint.name, stringResource(R.string.profile_paired_certain),
            colors.secondaryContainer, colors.onSecondaryContainer,
        )
        is WatchModelHint.Depends -> WatchStatusCard(
            Icons.Rounded.Watch, hint.name, stringResource(R.string.profile_paired_depends),
            colors.surfaceContainerHigh, colors.onSurface,
        ) {
            // Only worth saying while the version is still unknown.
            if (state.detected == null) {
                Text(
                    stringResource(R.string.profile_tip_wireless),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        // Once the watch told its version, Bluetooth has nothing left to add.
        null -> if (state.detected == null) when (state.pairedCheck) {
            PairedCheck.Denied -> WatchStatusCard(
                Icons.Rounded.BluetoothDisabled, null, stringResource(R.string.profile_check_denied),
                colors.surfaceContainerHigh, colors.onSurface,
                action = {
                    TextButton(onClick = { onEvent(ProfileEvent.AllowBluetooth) }) { Text(stringResource(R.string.profile_allow)) }
                },
            )
            PairedCheck.NoWatch -> WatchStatusCard(
                Icons.Rounded.Watch, null, stringResource(R.string.profile_check_none),
                colors.surfaceContainerHigh, colors.onSurface,
            )
            PairedCheck.Idle -> OutlinedAction(
                stringResource(R.string.profile_check_paired),
                { onEvent(ProfileEvent.CheckPairedWatches) },
                Icons.Rounded.Bluetooth,
            )
            PairedCheck.Done -> Unit
        }
    }
}

/** A calm "still looking" line while the watch's Wi-Fi announcement is awaited. */
@Composable
private fun ScanningRow() {
    Row(
        Modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.profile_scanning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One result of a version check: a round icon badge, an optional [title], the [body], optional
 * extra [content] under the body and an optional [action] button.
 */
@Composable
private fun WatchStatusCard(
    icon: ImageVector,
    title: String?,
    body: String,
    container: Color,
    content: Color,
    action: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    GroupedItem(0, 1, containerColor = container, contentColor = content, contentPadding = PaddingValues(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(content.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(body, style = MaterialTheme.typography.bodyMedium)
                extra?.invoke()
                action?.let { Box(Modifier.align(Alignment.End)) { it() } }
            }
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
