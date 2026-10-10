package io.openshm.patcher.ui.screens.install

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold

/** A grant applied after an install (permission, app op, setting, listener, battery exemption). */
data class GrantRow(val grant: Grant, val status: CheckStatus) {
    /** Listener access and restricted settings are switches only the user can flip. */
    val needsUser: Boolean
        get() = grant.kind == GrantKind.NotificationListener || grant.name == "ACCESS_RESTRICTED_SETTINGS"
}

data class InstallItem(
    val outputId: String,
    val label: String,
    val packageName: String,
    val status: CheckStatus,
    val grants: List<GrantRow> = emptyList(),
    /** 0..1 while the package is being sent to the device; null otherwise. */
    val progress: Float? = null,
)

data class InstallUiState(
    val items: List<InstallItem> = emptyList(),
    val running: Boolean = false,
    @StringRes val errorRes: Int? = null,
    /** Extra detail for [errorRes], such as the package manager's refusal code; shown as is. */
    val errorDetail: String? = null,
) {
    val done: Boolean get() = items.isNotEmpty() && items.all { item ->
        item.status == CheckStatus.Passed && item.grants.all {
            it.status != CheckStatus.Pending && it.status != CheckStatus.Running && it.status != CheckStatus.Failed
        }
    }
}

sealed interface InstallEvent {
    data object Start : InstallEvent
    data class OpenGrantSettings(val outputId: String, val grantName: String) : InstallEvent
}

@Composable
fun InstallPhoneScreen(
    state: InstallUiState,
    onEvent: (InstallEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = InstallStep(
    step = SetupStep.InstallPhone,
    title = stringResource(R.string.install_phone_title),
    intro = stringResource(R.string.install_phone_intro),
    note = stringResource(R.string.install_phone_note),
    state = state,
    onEvent = onEvent,
    onContinue = onContinue,
    onBack = onBack,
    modifier = modifier,
    accent = Accent.Primary,
)

@Composable
fun WatchInstallScreen(
    state: InstallUiState,
    onEvent: (InstallEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = InstallStep(
    step = SetupStep.WatchInstall,
    title = stringResource(R.string.install_watch_title),
    intro = stringResource(R.string.install_watch_intro),
    note = stringResource(R.string.install_watch_note),
    state = state,
    onEvent = onEvent,
    onContinue = onContinue,
    onBack = onBack,
    modifier = modifier,
    accent = Accent.Tertiary,
)

@Composable
private fun InstallStep(
    step: SetupStep,
    title: String,
    intro: String,
    note: String,
    state: InstallUiState,
    onEvent: (InstallEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
    accent: Accent,
) {
    StepScreenScaffold(
        step = step,
        title = title,
        onBack = onBack,
        modifier = modifier,
        footer = {
            if (state.done) {
                PrimaryAction(stringResource(R.string.action_continue), onContinue, accent = accent)
            } else {
                PrimaryAction(
                    stringResource(if (state.errorRes != null) R.string.action_retry else R.string.install_start),
                    { onEvent(InstallEvent.Start) },
                    enabled = !state.running,
                    accent = accent,
                )
            }
        },
    ) {
        Text(intro, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.errorRes?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Failed, size = 24.dp)
                Column {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                    state.errorDetail?.let { detail -> PackageId(detail, maxLines = 3) }
                }
            }
        }
        state.items.forEach { item ->
            Column {
                SectionTitle(item.label)
                Column(verticalArrangement = GroupedSpacing) {
                    GroupedItem(0, 1 + item.grants.size) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusShape(item.status)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PackageId(item.packageName, maxLines = 2)
                                item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                            }
                        }
                    }
                    item.grants.forEachIndexed { i, row ->
                        GroupedItem(i + 1, 1 + item.grants.size) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusShape(row.status, size = 22.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(row.grant.kind.labelRes()), style = MaterialTheme.typography.labelMedium)
                                    PackageId(row.grant.name)
                                }
                                if (row.grant.optional) InfoChip(stringResource(R.string.chip_optional))
                                if (row.needsUser && row.status != CheckStatus.Passed) {
                                    TextButton(onClick = { onEvent(InstallEvent.OpenGrantSettings(item.outputId, row.grant.name)) }) {
                                        Text(stringResource(R.string.action_open_settings))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        NoteRow(Icons.Rounded.Info, note, Modifier.padding(top = 4.dp))
    }
}

@StringRes
private fun GrantKind.labelRes(): Int = when (this) {
    GrantKind.Permission -> R.string.grant_permission
    GrantKind.AppOp -> R.string.grant_appop
    GrantKind.SecureSetting -> R.string.grant_setting
    GrantKind.NotificationListener -> R.string.grant_listener
    GrantKind.BatteryExemption -> R.string.grant_battery
}
