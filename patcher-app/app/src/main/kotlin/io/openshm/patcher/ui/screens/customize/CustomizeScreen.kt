package io.openshm.patcher.ui.screens.customize

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.PackagePrefix
import io.openshm.patcher.data.PatchInfo
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.theme.PatcherTheme

/** One patch row. Every patch in the catalogue is required today; optional ones will set [required] false. */
data class CustomizePatch(val info: PatchInfo, val required: Boolean = true, val enabled: Boolean = true)

/** The patches of one input app, shown as a group with the app and the device it goes to. */
data class CustomizeGroup(
    val inputId: String,
    val label: String,
    val device: Device,
    val patches: List<CustomizePatch>,
)

enum class Preset { Recommended, Minimal, Everything }

data class CustomizeUiState(
    val groups: List<CustomizeGroup> = emptyList(),
    val preset: Preset = Preset.Recommended,
    val packagePrefix: String = "",
    val prefixLocked: Boolean = false,
    val prefixProblem: PackagePrefix.Problem? = null,
    val advancedExpanded: Boolean = false,
) {
    val optionalCount: Int get() = groups.sumOf { g -> g.patches.count { !it.required } }

    /** Presets only make sense when the user can actually switch something. */
    val showPresets: Boolean get() = optionalCount > 0
    val enabledPatchCount: Int get() = groups.sumOf { g -> g.patches.count { it.required || it.enabled } }
    val appCount: Int get() = groups.size
    val canBuild: Boolean get() = prefixProblem == null && enabledPatchCount > 0
}

sealed interface CustomizeEvent {
    data class TogglePatch(val inputId: String, val patchId: String) : CustomizeEvent
    data class SelectPreset(val preset: Preset) : CustomizeEvent
    data object ToggleAdvanced : CustomizeEvent
    data class PrefixChanged(val prefix: String) : CustomizeEvent
    data object RegeneratePrefix : CustomizeEvent
}

@Composable
fun CustomizeScreen(
    state: CustomizeUiState,
    onEvent: (CustomizeEvent) -> Unit,
    onBuild: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScreenScaffold(
        step = SetupStep.Customize,
        title = stringResource(R.string.customize_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            FooterHint(
                pluralStringResource(R.plurals.customize_summary_patches, state.enabledPatchCount, state.enabledPatchCount) +
                    " · " + pluralStringResource(R.plurals.customize_summary_apps, state.appCount, state.appCount),
            )
            PrimaryAction(
                stringResource(R.string.customize_build), onBuild,
                icon = Icons.Rounded.Construction, enabled = state.canBuild,
            )
        },
    ) {
        Text(
            stringResource(R.string.customize_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.showPresets) PresetRow(state.preset) { onEvent(CustomizeEvent.SelectPreset(it)) }
        state.groups.forEach { group ->
            Column {
                GroupHeader(group)
                Column(verticalArrangement = GroupedSpacing) {
                    group.patches.forEachIndexed { index, patch ->
                        PatchRow(index, group.patches.size, patch) { onEvent(CustomizeEvent.TogglePatch(group.inputId, patch.info.id)) }
                    }
                }
            }
        }
        AdvancedCard(state, onEvent)
    }
}

@Composable
private fun PresetRow(selected: Preset, onSelect: (Preset) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        Preset.entries.forEachIndexed { index, preset ->
            SegmentedButton(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                shape = SegmentedButtonDefaults.itemShape(index, Preset.entries.size),
            ) {
                Text(
                    stringResource(
                        when (preset) {
                            Preset.Recommended -> R.string.preset_recommended
                            Preset.Minimal -> R.string.preset_minimal
                            Preset.Everything -> R.string.preset_everything
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: CustomizeGroup) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(group.label, Modifier.weight(1f, fill = false))
        InfoChip(
            stringResource(if (group.device == Device.Phone) R.string.device_phone else R.string.device_watch),
            icon = if (group.device == Device.Phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Watch,
        )
    }
}

@Composable
private fun PatchRow(index: Int, count: Int, patch: CustomizePatch, onToggle: () -> Unit) {
    val on = patch.required || patch.enabled
    val requiredText = stringResource(R.string.customize_required)
    GroupedItem(
        index, count,
        modifier = Modifier.toggleable(
            value = on,
            enabled = !patch.required,
            role = Role.Switch,
            onValueChange = { onToggle() },
        ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(patch.info.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    patch.info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (patch.required) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = requiredText,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Switch(checked = on, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun AdvancedCard(state: CustomizeUiState, onEvent: (CustomizeEvent) -> Unit) {
    val stateText = stringResource(if (state.advancedExpanded) R.string.state_expanded else R.string.state_collapsed)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onEvent(CustomizeEvent.ToggleAdvanced) }
                    .semantics { stateDescription = stateText }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.customize_advanced),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Rounded.ExpandMore, contentDescription = null,
                    modifier = Modifier.rotate(if (state.advancedExpanded) 180f else 0f),
                )
            }
            AnimatedVisibility(state.advancedExpanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.customize_prefix_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.packagePrefix,
                        onValueChange = { onEvent(CustomizeEvent.PrefixChanged(it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.customize_prefix_label)) },
                        singleLine = true,
                        enabled = !state.prefixLocked,
                        textStyle = PatcherTheme.text.mono,
                        isError = state.prefixProblem != null,
                        supportingText = {
                            Text(
                                stringResource(
                                    when {
                                        state.prefixLocked -> R.string.customize_prefix_locked
                                        state.prefixProblem != null -> state.prefixProblem.messageRes()
                                        else -> R.string.customize_prefix_ok
                                    },
                                ),
                            )
                        },
                        trailingIcon = {
                            if (!state.prefixLocked) {
                                IconAction(
                                    Icons.Rounded.Refresh,
                                    stringResource(R.string.customize_prefix_regenerate),
                                    { onEvent(CustomizeEvent.RegeneratePrefix) },
                                )
                            }
                        },
                    )
                    PackageId("${state.packagePrefix}.shmbridge.phone")
                }
            }
        }
    }
}

private fun PackagePrefix.Problem.messageRes() = when (this) {
    PackagePrefix.Problem.Empty -> R.string.prefix_problem_empty
    PackagePrefix.Problem.TooShort -> R.string.prefix_problem_short
    PackagePrefix.Problem.TooLong -> R.string.prefix_problem_long
    PackagePrefix.Problem.InvalidSegment -> R.string.prefix_problem_segment
    PackagePrefix.Problem.Reserved -> R.string.prefix_problem_reserved
}
