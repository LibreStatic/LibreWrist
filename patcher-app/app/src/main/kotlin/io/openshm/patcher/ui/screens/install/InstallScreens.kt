package io.openshm.patcher.ui.screens.install

import io.openshm.patcher.ui.components.ActionRow
import io.openshm.patcher.ui.components.InstructionText
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.openshm.patcher.ui.components.AppErrorDialog
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.SuccessOverlay
import io.openshm.patcher.ui.components.PAIRING_CODE_LENGTH
import io.openshm.patcher.ui.components.PairingCodeField
import io.openshm.patcher.ui.components.ContainerTextAction
import io.openshm.patcher.ui.components.QuietAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Grant
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.GrantKind
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.LocalTwoPane
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.AutoTextButton
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.UnattendedAttention
import io.openshm.patcher.ui.components.observeUserInput
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.theme.PatcherTheme

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
enum class SelfAdbPhase { Reconnecting, WaitingForService, WaitingForCode, WrongCode, Ambiguous, Connecting, Granting, Failed }

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
    /** How the last Wireless debugging grant run ended, until the screen has shown it; phone screen only. */
    val adbOutcome: AdbOutcome? = null,
    /** Packages of an earlier prefix's bridge or Samsung Health+ that Android is being asked to remove right now; phone screen only. */
    val removingLeftovers: List<String> = emptyList(),
    /** Packages of an earlier prefix's bridge or Samsung Health+ that stayed because the removal was declined or failed. */
    val leftoversKept: List<String> = emptyList(),
    /** Copies of an earlier prefix still on the phone, as last read by a refresh; the screen offers to remove them. */
    val leftoversFound: List<String> = emptyList(),
    /** The phone install over Wireless debugging was blocked by the phone; the screen offers the platform installer. */
    val adbInstallRestricted: Boolean = false,
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

    /** Open the phone's Developer options, at Wireless debugging where the system allows it. */
    data object OpenWirelessDebugging : InstallEvent

    /** "Uninstall and install" was tapped: opens the confirmation, removes nothing. */
    data object RequestReinstall : InstallEvent
    data object CancelReinstall : InstallEvent

    /** The user confirmed losing the installed copy's data: uninstall it, then install again. */
    data object ConfirmReinstall : InstallEvent

    /** "Remove old copies" was tapped: removes only the leftover copies of an earlier prefix. */
    data object RemoveLeftovers : InstallEvent

    /** The error dialog of a Wireless debugging run was acknowledged, or its success overlay ran out. */
    data object AcknowledgeAdbOutcome : InstallEvent

    /** Install with the system's own confirmation, after the install over Wireless debugging was blocked (phone only). */
    data object InstallWithConfirmation : InstallEvent
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

/** The watch could not be reached: what to check, with the raw error as a detail line. Retry is the footer action. */
@Composable
private fun WatchUnreachableCard(detail: String?) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Failed, size = 24.dp)
                Text(
                    stringResource(R.string.watch_unreachable_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                stringResource(R.string.watch_unreachable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            detail?.let { PackageId(it, maxLines = 3) }
        }
    }
}

/**
 * An earlier setup left copies of the bridge or Samsung Health+ under another package name. While
 * Android asks to remove them the card explains the system dialog; if the user declined, it says
 * what that leaves; otherwise it lists what was found and offers to remove it.
 */
@Composable
private fun LeftoverRemovalCard(removing: List<String>, kept: List<String>, found: List<String>, onRemove: () -> Unit) {
    val isRemoving = removing.isNotEmpty()
    val isKept = !isRemoving && kept.isNotEmpty()
    val packages = if (isRemoving) removing else if (isKept) kept else found
    Surface(
        shape = MaterialTheme.shapes.large,
        color = PatcherTheme.status.warningContainer,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Warning, size = 24.dp)
                Text(
                    stringResource(
                        if (isRemoving) R.string.leftover_removing_title else if (isKept) R.string.leftover_kept_title else R.string.leftover_found_title,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = PatcherTheme.status.onWarningContainer,
                )
            }
            Text(
                stringResource(
                    if (isRemoving) R.string.leftover_removing_body else if (isKept) R.string.leftover_kept_body else R.string.leftover_found_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = PatcherTheme.status.onWarningContainer,
            )
            packages.forEach { PackageId(it, maxLines = 2) }
            if (!isRemoving) {
                ContainerTextAction(
                    stringResource(R.string.leftover_remove_action),
                    onRemove,
                    PatcherTheme.status.onWarningContainer,
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
            }
        }
    }
}

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
    UnattendedAttention(installNeedsUser(state, phone))
    // Unfinished apps first, so what blocks Continue is visible without scrolling.
    val items = state.items.sortedBy { it.isComplete }
    val split = twoPaneSplit(items.map { it.rowCount })
    StepScreenScaffold(
        step = step,
        title = title,
        onBack = onBack,
        modifier = modifier,
        banner = if (phone && (state.removingLeftovers.isNotEmpty() || state.leftoversKept.isNotEmpty() || (state.leftoversFound.isNotEmpty() && !state.running))) {
            { LeftoverRemovalCard(state.removingLeftovers, state.leftoversKept, state.leftoversFound) { onEvent(InstallEvent.RemoveLeftovers) } }
        } else null,
        footer = {
            if (state.done) {
                PrimaryAction(stringResource(R.string.action_continue), onContinue, accent = accent, auto = installFooterAuto(state, phone))
            } else if (phone && state.canSkipGrants) {
                ActionRow {
                    PrimaryAction(stringResource(R.string.install_check_again), { onEvent(InstallEvent.Refresh) }, accent = accent)
                    QuietAction(stringResource(R.string.install_continue_anyway), onContinue, accent = accent)
                }
            } else {
                PrimaryAction(
                    stringResource(if (state.errorRes != null) R.string.action_retry else R.string.install_start),
                    { onEvent(InstallEvent.Start) },
                    enabled = !state.running,
                    accent = accent,
                    auto = installFooterAuto(state, phone),
                )
            }
        },
        secondary = {
            // Wide: the list is split over both panes, so the right one continues it before the notes.
            if (LocalTwoPane.current) items.drop(split).forEach { InstallItemGroup(it, state, onEvent) }
            if (phone) SelfAdbSection(state, onEvent)
            NoteRow(Icons.Rounded.Info, note, Modifier.padding(top = 4.dp))
        },
    ) {
        IntroText(intro)
        if (state.errorRes == R.string.watch_error_unreachable) {
            WatchUnreachableCard(state.errorDetail)
        } else state.errorRes?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusShape(CheckStatus.Failed, size = 24.dp)
                Column {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium)
                    state.errorDetail?.let { detail -> PackageId(detail, maxLines = 3) }
                }
            }
        }
        if (phone && state.adbInstallRestricted && !state.running) {
            OutlinedAction(stringResource(R.string.action_install_with_confirmation), { onEvent(InstallEvent.InstallWithConfirmation) }, Icons.Rounded.Settings)
        }
        if (state.needsUnknownSources) {
            OutlinedAction(stringResource(R.string.action_allow_installing), { onEvent(InstallEvent.OpenUnknownSources) }, Icons.Rounded.Settings)
        }
        val shown = if (LocalTwoPane.current) items.take(split) else items
        shown.forEach { InstallItemGroup(it, state, onEvent) }
    }
    if (phone) {
        when (val outcome = state.adbOutcome) {
            null -> Unit
            AdbOutcome.Success -> SuccessOverlay(
                title = stringResource(R.string.phone_adb_success_title),
                message = stringResource(R.string.phone_adb_success_message),
                onFinished = {
                    onEvent(InstallEvent.AcknowledgeAdbOutcome)
                    onContinue()
                },
            )
            is AdbOutcome.Failure -> AppErrorDialog(
                title = stringResource(R.string.phone_adb_error_title),
                message = stringResource(R.string.phone_adb_error_message),
                detail = outcome.detail,
                onAcknowledge = { onEvent(InstallEvent.AcknowledgeAdbOutcome) },
            )
        }
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

private val InstallItem.isComplete: Boolean
    get() = status == CheckStatus.Passed && grants.all { it.status == CheckStatus.Passed || it.grant.optional }

private val InstallItem.rowCount: Int get() = 1 + grants.size + if (onboarding) 1 else 0

/**
 * Where a two-pane layout splits the list: the first pane takes items until it holds about half the
 * rows, so two long panes replace one scrolling list.
 */
internal fun twoPaneSplit(rows: List<Int>): Int {
    if (rows.size < 2) return rows.size
    val half = rows.sum() / 2.0
    var acc = 0
    rows.forEachIndexed { i, r ->
        if (acc + r / 2.0 >= half) return maxOf(i, 1)
        acc += r
    }
    return rows.size - 1
}

@Composable
private fun InstallItemGroup(item: InstallItem, state: InstallUiState, onEvent: (InstallEvent) -> Unit) {
        val rowCount = item.rowCount
        Column {
            SectionTitle(item.label)
            Column(verticalArrangement = GroupedSpacing) {
                GroupedItem(0, rowCount) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusShape(item.status)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            PackageId(item.packageName, maxLines = 2)
                            item.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                        }
                        // Trailing, like the settings action of a grant row.
                        if (state.conflict?.outputId == item.outputId && !state.running) {
                            OutlinedButton(
                                onClick = { onEvent(InstallEvent.RequestReinstall) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            ) {
                                Text(stringResource(R.string.reinstall_action))
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

@Composable
private fun IntroText(intro: String) =
    InstructionText(intro, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Asks before the installed copy is removed: its data is lost and this cannot be undone. */
@Composable
internal fun ReinstallDialog(label: String, device: Device, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val uninstallActive = Automation.state.collectAsStateWithLifecycle().value.uninstallActive
    AlertDialog(
        onDismissRequest = onDismiss,
        // The dialog is its own window: it reports touches itself so they pause the countdown.
        modifier = Modifier.observeUserInput(),
        title = { Text(stringResource(R.string.reinstall_dialog_title, label)) },
        text = {
            Text(stringResource(if (device == Device.Phone) R.string.reinstall_dialog_body_phone else R.string.reinstall_dialog_body_watch))
        },
        confirmButton = {
            AutoTextButton(
                stringResource(R.string.reinstall_action), onConfirm, auto = uninstallActive,
                color = MaterialTheme.colorScheme.error,
            )
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
        // A plain header, not primary-coloured: the primary colour reads as something to tap.
        SectionTitle(stringResource(R.string.phone_adb_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (phase == null) {
            InstructionText(
                stringResource(R.string.phone_adb_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (installed && !state.done && !state.running) {
                SelfAdbGuide()
                ActionRow {
                    OutlinedAction(stringResource(R.string.phone_adb_action), { onEvent(InstallEvent.GrantWithAdb) }, Icons.Rounded.Wifi)
                    OpenWirelessDebuggingAction(onEvent)
                }
            }
            return@Column
        }
        // While the code is asked for, the field comes first and the guide stays below it.
        val askingForCode = phase == SelfAdbPhase.WaitingForCode || phase == SelfAdbPhase.WrongCode
        val guided = askingForCode || phase == SelfAdbPhase.Reconnecting || phase == SelfAdbPhase.WaitingForService
        if (guided && !askingForCode) SelfAdbGuide()
        SelfAdbProgress(
            phase,
            onSubmitCode = { onEvent(InstallEvent.SubmitPairingCode(it)) },
            onRetry = { onEvent(InstallEvent.GrantWithAdb) },
            onCancel = { onEvent(InstallEvent.CancelAdb) },
        )
        if (guided) {
            if (askingForCode) SelfAdbGuide()
            OpenWirelessDebuggingAction(onEvent)
        }
    }
}

@Composable
private fun OpenWirelessDebuggingAction(onEvent: (InstallEvent) -> Unit) {
    QuietAction(stringResource(R.string.selfadb_open_wireless), { onEvent(InstallEvent.OpenWirelessDebugging) })
}

private const val SUBMIT_GUARD_MS = 5_000L

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
    var code by rememberSaveable { mutableStateOf("") }
    var failures by rememberSaveable { mutableIntStateOf(0) }
    var wrongCounted by rememberSaveable { mutableStateOf(false) }
    // A code went out and the controller has not moved on yet: no second submission in that gap.
    var submitted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(phase) {
        submitted = false
        if (phase == SelfAdbPhase.WrongCode) {
            if (!wrongCounted) {
                wrongCounted = true
                failures++
                code = ""
            }
        } else {
            wrongCounted = false
        }
    }
    LaunchedEffect(submitted) {
        // Safety net: never leave the field read-only if the controller answers with nothing new.
        if (submitted) {
            delay(SUBMIT_GUARD_MS)
            submitted = false
        }
    }
    val busy = submitted || phase == SelfAdbPhase.Connecting
    fun submit(value: String) {
        if (busy || value.length != PAIRING_CODE_LENGTH) return
        submitted = true
        onSubmitCode(value)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstructionText(stringResource(phase.messageRes()), style = MaterialTheme.typography.bodyMedium)
        if (phase == SelfAdbPhase.WaitingForCode || phase == SelfAdbPhase.WrongCode || phase == SelfAdbPhase.Connecting) {
            PairingCodeField(
                code = code,
                onCodeChange = { typed ->
                    val completes = code.length != PAIRING_CODE_LENGTH && typed.length == PAIRING_CODE_LENGTH
                    code = typed
                    // Only the edit that completes the code submits; a repeated value does not.
                    if (completes) submit(typed)
                },
                label = stringResource(R.string.phone_adb_code_label),
                modifier = Modifier.fillMaxWidth(),
                error = if (phase == SelfAdbPhase.WrongCode && code.isEmpty()) stringResource(R.string.phone_adb_wrong_code) else null,
                shakeKey = failures,
                busy = busy,
            )
            OutlinedAction(
                stringResource(R.string.phone_adb_submit),
                { submit(code) },
                Icons.Rounded.Check,
                enabled = !busy && code.length == PAIRING_CODE_LENGTH,
            )
        }
        ActionRow {
            if (phase == SelfAdbPhase.Failed || phase == SelfAdbPhase.Ambiguous) {
                OutlinedAction(stringResource(retryLabel), onRetry, Icons.Rounded.Refresh)
            }
            QuietAction(stringResource(R.string.action_cancel), onCancel)
        }
    }
}

@StringRes
internal fun SelfAdbPhase.messageRes(): Int = when (this) {
    SelfAdbPhase.WaitingForService -> R.string.phone_adb_waiting_service
    SelfAdbPhase.WaitingForCode -> R.string.phone_adb_waiting_code
    SelfAdbPhase.WrongCode -> R.string.phone_adb_wrong_code
    SelfAdbPhase.Ambiguous -> R.string.phone_adb_ambiguous
    SelfAdbPhase.Reconnecting -> R.string.phone_adb_reconnecting
    SelfAdbPhase.Connecting -> R.string.phone_adb_connecting
    SelfAdbPhase.Granting -> R.string.phone_adb_granting
    SelfAdbPhase.Failed -> R.string.phone_adb_failed
}

@StringRes
private fun GrantKind.labelRes(): Int = when (this) {
    GrantKind.Permission -> R.string.grant_permission
    GrantKind.AppOp -> R.string.grant_appop
    GrantKind.SecureSetting -> R.string.grant_setting
    GrantKind.GlobalSetting -> R.string.grant_global_setting
    GrantKind.NotificationListener -> R.string.grant_listener
    GrantKind.BatteryExemption -> R.string.grant_battery
}
