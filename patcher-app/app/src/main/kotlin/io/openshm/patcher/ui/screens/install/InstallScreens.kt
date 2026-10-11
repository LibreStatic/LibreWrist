package io.openshm.patcher.ui.screens.install

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.QuietAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import io.openshm.patcher.data.Device
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
data class GrantRow(val grant: Grant, val status: CheckStatus, val manual: Boolean = false) {
    /**
     * Listener access and restricted settings are switches only the user can flip; [manual] marks
     * any other grant the app cannot apply itself (everything but the listener on this phone).
     */
    val needsUser: Boolean
        get() = manual || grant.kind == GrantKind.NotificationListener || grant.name == "ACCESS_RESTRICTED_SETTINGS"
}

data class InstallItem(
    val outputId: String,
    val label: String,
    val packageName: String,
    val status: CheckStatus,
    val grants: List<GrantRow> = emptyList(),
    /** 0..1 while the package is being sent to the device; null otherwise. */
    val progress: Float? = null,
    /** The app has a setup of its own the user finishes after the install (informational, never blocks). */
    val onboarding: Boolean = false,
)

/**
 * An installed copy of one of this build's outputs that another key signed, so it cannot be
 * updated. The only way on is to remove it, which needs the user's explicit confirmation.
 */
data class SignatureConflict(val outputId: String, val packageName: String, val device: Device)

/** Where the Wireless debugging fallback ("grant everything") is. */
enum class SelfAdbPhase { WaitingForService, WaitingForCode, WrongCode, Ambiguous, Connecting, Granting, Failed }

data class InstallUiState(
    val items: List<InstallItem> = emptyList(),
    val running: Boolean = false,
    @StringRes val errorRes: Int? = null,
    /** Extra detail for [errorRes], such as the package manager's refusal code; shown as is. */
    val errorDetail: String? = null,
    /** The install stopped because this app may not install packages yet; the screen offers the setting. */
    val needsUnknownSources: Boolean = false,
    /** The Wireless debugging fallback in progress, or null. */
    val selfAdb: SelfAdbPhase? = null,
    /** The install was refused because another key signed the installed copy; the screen offers to replace it. */
    val conflict: SignatureConflict? = null,
    /** The confirmation dialog of the reinstall fallback is open (only with a [conflict]). */
    val confirmingReinstall: Boolean = false,
) {
    /** Everything is installed but some access is still open: the user may go on without it. */
    val canSkipGrants: Boolean get() = !running && !done && items.isNotEmpty() && items.all { it.status == CheckStatus.Passed }

    val done: Boolean get() = items.isNotEmpty() && items.all { item ->
        item.status == CheckStatus.Passed && item.grants.all {
            it.status != CheckStatus.Pending && it.status != CheckStatus.Running && it.status != CheckStatus.Failed
        }
    }
}

sealed interface InstallEvent {
    data object Start : InstallEvent
    data class OpenGrantSettings(val outputId: String, val grantName: String) : InstallEvent

    /** The screen came back to the front: read the access state again. */
    data object Refresh : InstallEvent
    data object OpenUnknownSources : InstallEvent
    data class OpenApp(val outputId: String) : InstallEvent

    /** Grant everything through this phone's own Wireless debugging. */
    data object GrantWithAdb : InstallEvent
    data class SubmitPairingCode(val code: String) : InstallEvent
    data object CancelAdb : InstallEvent

    /** "Uninstall and install" was tapped: opens the confirmation, removes nothing. */
    data object RequestReinstall : InstallEvent
    data object CancelReinstall : InstallEvent

    /** The user confirmed losing the installed copy's data: uninstall it, then install again. */
    data object ConfirmReinstall : InstallEvent
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
    phone = true,
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
    phone: Boolean = false,
) {
    StepScreenScaffold(
        step = step,
        title = title,
        onBack = onBack,
        modifier = modifier,
        footer = {
            if (state.done) {
                PrimaryAction(stringResource(R.string.action_continue), onContinue, accent = accent)
            } else if (phone && state.canSkipGrants) {
                PrimaryAction(stringResource(R.string.install_check_again), { onEvent(InstallEvent.Refresh) }, accent = accent)
                QuietAction(stringResource(R.string.install_continue_anyway), onContinue, accent = accent)
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
        if (state.needsUnknownSources) {
            OutlinedAction(stringResource(R.string.action_allow_installing), { onEvent(InstallEvent.OpenUnknownSources) }, Icons.Rounded.Settings)
        }
        state.items.forEach { item ->
            val rowCount = 1 + item.grants.size + if (item.onboarding) 1 else 0
            Column {
                SectionTitle(item.label)
                Column(verticalArrangement = GroupedSpacing) {
                    GroupedItem(0, rowCount) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusShape(item.status)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                PackageId(item.packageName, maxLines = 2)
                                item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                                if (state.conflict?.outputId == item.outputId && !state.running) {
                                    TextButton(
                                        onClick = { onEvent(InstallEvent.RequestReinstall) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) {
                                        Text(stringResource(R.string.reinstall_action))
                                    }
                                }
                            }
                        }
                    }
                    item.grants.forEachIndexed { i, row ->
                        GroupedItem(i + 1, rowCount) {
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
                    if (item.onboarding) {
                        GroupedItem(rowCount - 1, rowCount) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.install_onboarding),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                if (item.status == CheckStatus.Passed) {
                                    TextButton(onClick = { onEvent(InstallEvent.OpenApp(item.outputId)) }) {
                                        Text(stringResource(R.string.action_open))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (phone) SelfAdbSection(state, onEvent)
        NoteRow(Icons.Rounded.Info, note, Modifier.padding(top = 4.dp))
    }
    val conflict = state.conflict
    if (state.confirmingReinstall && conflict != null) {
        ReinstallDialog(
            label = state.items.firstOrNull { it.outputId == conflict.outputId }?.label ?: conflict.packageName,
            device = conflict.device,
            onConfirm = { onEvent(InstallEvent.ConfirmReinstall) },
            onDismiss = { onEvent(InstallEvent.CancelReinstall) },
        )
    }
}

/** Asks before the installed copy is removed: its data is lost and this cannot be undone. */
@Composable
internal fun ReinstallDialog(label: String, device: Device, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reinstall_dialog_title, label)) },
        text = {
            Text(stringResource(if (device == Device.Phone) R.string.reinstall_dialog_body_phone else R.string.reinstall_dialog_body_watch))
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(stringResource(R.string.reinstall_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The Wireless debugging fallback: starts it, shows where it is, and takes the pairing code. */
@Composable
private fun SelfAdbSection(state: InstallUiState, onEvent: (InstallEvent) -> Unit) {
    val installed = state.items.isNotEmpty() && state.items.all { it.status == CheckStatus.Passed }
    val phase = state.selfAdb
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.phone_adb_title))
        if (phase == null) {
            Text(
                stringResource(R.string.phone_adb_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (installed && !state.done && !state.running) {
                OutlinedAction(stringResource(R.string.phone_adb_action), { onEvent(InstallEvent.GrantWithAdb) }, Icons.Rounded.Wifi)
            }
            return@Column
        }
        SelfAdbProgress(
            phase,
            onSubmitCode = { onEvent(InstallEvent.SubmitPairingCode(it)) },
            onRetry = { onEvent(InstallEvent.GrantWithAdb) },
            onCancel = { onEvent(InstallEvent.CancelAdb) },
        )
    }
}

/**
 * Where the Wireless debugging fallback is, with the pairing code field and the retry and cancel
 * actions; shared by the install screen and the pairing troubleshooting.
 */
@Composable
internal fun SelfAdbProgress(
    phase: SelfAdbPhase,
    onSubmitCode: (String) -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    @StringRes retryLabel: Int = R.string.phone_adb_action,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(phase.messageRes()), style = MaterialTheme.typography.bodyMedium)
        if (phase == SelfAdbPhase.WaitingForCode || phase == SelfAdbPhase.WrongCode) {
            var code by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.phone_adb_code_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            OutlinedAction(stringResource(R.string.phone_adb_submit), { onSubmitCode(code) }, Icons.Rounded.Check)
        }
        if (phase == SelfAdbPhase.Failed || phase == SelfAdbPhase.Ambiguous) {
            OutlinedAction(stringResource(retryLabel), onRetry, Icons.Rounded.Refresh)
        }
        QuietAction(stringResource(R.string.action_cancel), onCancel)
    }
}

@StringRes
internal fun SelfAdbPhase.messageRes(): Int = when (this) {
    SelfAdbPhase.WaitingForService -> R.string.phone_adb_waiting_service
    SelfAdbPhase.WaitingForCode -> R.string.phone_adb_waiting_code
    SelfAdbPhase.WrongCode -> R.string.phone_adb_wrong_code
    SelfAdbPhase.Ambiguous -> R.string.phone_adb_ambiguous
    SelfAdbPhase.Connecting -> R.string.phone_adb_connecting
    SelfAdbPhase.Granting -> R.string.phone_adb_granting
    SelfAdbPhase.Failed -> R.string.phone_adb_failed
}

@StringRes
private fun GrantKind.labelRes(): Int = when (this) {
    GrantKind.Permission -> R.string.grant_permission
    GrantKind.AppOp -> R.string.grant_appop
    GrantKind.SecureSetting -> R.string.grant_setting
    GrantKind.NotificationListener -> R.string.grant_listener
    GrantKind.BatteryExemption -> R.string.grant_battery
}
