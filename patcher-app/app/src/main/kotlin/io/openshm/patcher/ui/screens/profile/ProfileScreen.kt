package io.openshm.patcher.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme
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
import io.openshm.patcher.ui.components.PolygonShape
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
    /** The watch answered on its own: the step shows why it is skipped, then moves on. */
    val skipping: Boolean = false,
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
        footer = { PrimaryAction(stringResource(R.string.action_continue), onContinue, enabled = !state.skipping) },
    ) {
        Text(
            stringResource(R.string.profile_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(state.skipping, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SkippingNotice()
        }
        Column(verticalArrangement = GroupedSpacing, modifier = Modifier.alpha(if (state.skipping) 0.38f else 1f)) {
            state.options.forEachIndexed { index, option ->
                val selected = option == state.selected
                GroupedItem(
                    index,
                    state.options.size,
                    modifier = Modifier.selectable(
                        selected = selected,
                        enabled = !state.skipping,
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

/** A large expressive check: the watch told us enough, so this step is skipped. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkippingNotice() {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        if (shown) 1f else 0.4f,
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "checkScale",
    )
    val rotation by animateFloatAsState(
        if (shown) 0f else -90f,
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "checkRotation",
    )
    val status = PatcherTheme.status
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(112.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation }
                .clip(PolygonShape(MaterialShapes.Cookie12Sided))
                .background(status.successContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = status.onSuccessContainer, modifier = Modifier.size(56.dp))
        }
        Text(
            stringResource(R.string.profile_skip_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.profile_skip_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
