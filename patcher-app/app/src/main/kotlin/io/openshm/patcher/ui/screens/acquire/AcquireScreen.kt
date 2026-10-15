package io.openshm.patcher.ui.screens.acquire

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.AppGroup
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.VariantChip
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.SplitAction
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.components.UnattendedAttention
import io.openshm.patcher.ui.components.WavyDownloadProgress

/** Where one input app stands. Sizes are in bytes so the screen formats them the same way everywhere. */
sealed interface AcquireProgress {
    data object Missing : AcquireProgress
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : AcquireProgress {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }

    /** The file is present and its SHA-256 matched `apks/inputs.json`. */
    data class Verified(val sizeBytes: Long) : AcquireProgress

    /** The file was rejected; [reason] is a string resource. */
    data class Rejected(val reason: Int) : AcquireProgress
}

data class AcquireCard(
    val input: InputApp,
    val progress: AcquireProgress = AcquireProgress.Missing,
    val moreWaysExpanded: Boolean = false,
)

data class OptionalAppToggle(val app: AppGroup, val enabled: Boolean)

data class AcquireUiState(
    val cards: List<AcquireCard> = emptyList(),
    /** One switch per optional app the catalogue offers. */
    val optionalApps: List<OptionalAppToggle> = emptyList(),
) {
    val waiting: Int get() = cards.count { it.progress !is AcquireProgress.Verified }
}

sealed interface AcquireEvent {
    data class OpenMirror(val inputId: String) : AcquireEvent
    data class ToggleMoreWays(val inputId: String) : AcquireEvent
    data class UseFile(val inputId: String) : AcquireEvent
    data class ToggleApp(val appId: String) : AcquireEvent
}

@Composable
fun AcquireScreen(
    state: AcquireUiState,
    onEvent: (AcquireEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val waiting = state.waiting
    // A download finishes by itself; a missing or rejected file needs the user.
    UnattendedAttention(state.cards.any { it.progress is AcquireProgress.Missing || it.progress is AcquireProgress.Rejected })
    StepScreenScaffold(
        step = SetupStep.Acquire,
        title = stringResource(R.string.acquire_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            if (waiting > 0) {
                FooterHint(pluralStringResource(R.plurals.acquire_waiting, waiting, waiting))
            }
            PrimaryAction(stringResource(R.string.action_continue), onContinue, enabled = waiting == 0, auto = true)
        },
        // The cards split across the panes (first half beside the intro); narrow, the same single column.
        secondary = {
            state.cards.drop((state.cards.size + 1) / 2).forEach { AcquireCardView(it, onEvent) }
            NoteRow(Icons.Rounded.VerifiedUser, stringResource(R.string.acquire_verify_note))
        },
    ) {
        Text(
            stringResource(R.string.acquire_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The switches come first: they decide which cards below are needed.
        state.optionalApps.forEach { OptionalAppCard(it, onEvent) }
        state.cards.take((state.cards.size + 1) / 2).forEach { AcquireCardView(it, onEvent) }
    }
}

@Composable
private fun OptionalAppCard(toggle: OptionalAppToggle, onEvent: (AcquireEvent) -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(toggle.app.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    toggle.app.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = toggle.enabled, onCheckedChange = { onEvent(AcquireEvent.ToggleApp(toggle.app.id)) })
        }
    }
}

@Composable
private fun AcquireCardView(card: AcquireCard, onEvent: (AcquireEvent) -> Unit) {
    val input = card.input
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(input.label, style = MaterialTheme.typography.titleSmall)
                    Text(input.versionName, style = MaterialTheme.typography.titleMediumEmphasized)
                }
                InfoChip(
                    stringResource(if (input.device == Device.Phone) R.string.device_phone else R.string.device_watch),
                    icon = if (input.device == Device.Phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Watch,
                )
            }
            PackageId(input.packageName)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                input.variantChips.forEach { InfoChip(stringResource(it.labelRes())) }
            }
            when (val progress = card.progress) {
                is AcquireProgress.Missing, is AcquireProgress.Rejected -> {
                    if (progress is AcquireProgress.Rejected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusShape(CheckStatus.Failed, size = 22.dp)
                            Text(stringResource(progress.reason), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    GetActions(card, onEvent)
                }
                is AcquireProgress.Downloading -> {
                    Text(
                        stringResource(
                            R.string.acquire_downloading,
                            (progress.fraction * 100).toInt(),
                            progress.doneBytes / MEGABYTE,
                            progress.totalBytes / MEGABYTE,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    WavyDownloadProgress(progress.fraction)
                }
                is AcquireProgress.Verified -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusShape(CheckStatus.Passed, size = 22.dp)
                    Text(
                        stringResource(R.string.acquire_verified, progress.sizeBytes / MEGABYTE),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun GetActions(card: AcquireCard, onEvent: (AcquireEvent) -> Unit) {
    val id = card.input.id
    if (!card.input.onApkMirror) {
        Text(
            stringResource(R.string.acquire_not_on_mirror, card.input.versionName),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton({ onEvent(AcquireEvent.UseFile(id)) }) { Text(stringResource(R.string.acquire_use_file)) }
        return
    }
    Column {
        SplitAction(
            text = stringResource(R.string.acquire_open_mirror),
            icon = Icons.Rounded.OpenInBrowser,
            onClick = { onEvent(AcquireEvent.OpenMirror(id)) },
            moreDescription = stringResource(R.string.acquire_more_ways),
            expanded = card.moreWaysExpanded,
            onToggleMore = { onEvent(AcquireEvent.ToggleMoreWays(id)) },
        )
        DropdownMenu(expanded = card.moreWaysExpanded, onDismissRequest = { onEvent(AcquireEvent.ToggleMoreWays(id)) }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.acquire_use_file)) },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                onClick = { onEvent(AcquireEvent.UseFile(id)) },
            )
        }
    }
}

private const val MEGABYTE = 1024L * 1024L

internal fun VariantChip.labelRes() = when (this) {
    VariantChip.ApkNotBundle -> R.string.chip_apk_not_bundle
    VariantChip.NoDpi -> R.string.chip_nodpi
    VariantChip.MinAndroid11 -> R.string.chip_android_11
    VariantChip.MinAndroid14 -> R.string.chip_android_14
}
