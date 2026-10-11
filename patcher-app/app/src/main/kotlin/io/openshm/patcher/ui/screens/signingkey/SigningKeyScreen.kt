package io.openshm.patcher.ui.screens.signingkey

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import io.openshm.patcher.R
import io.openshm.patcher.data.PickedPassword
import io.openshm.patcher.ui.screens.build.ManualBackupDialog
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.TopActions
import io.openshm.patcher.ui.theme.PatcherTheme
import kotlinx.coroutines.launch

@Composable
fun SigningKeyScreen(
    state: SigningKeyUiState,
    onEvent: (SigningKeyEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Asks the system password manager for a saved password; null when the user declined. The caller wipes the array. */
    onPickSavedPassword: suspend () -> PickedPassword? = { null },
    /** The generated password of the private copy; the caller of this lambda wipes the array. */
    onRevealLocalPassword: suspend () -> CharArray? = { null },
    /** Saves to Documents/OpenSHM with a typed password; the dialog wipes the arrays after the call. */
    onSaveWithPassword: suspend (CharArray, CharArray) -> Boolean = { _, _ -> false },
) {
    var manualDialog by remember { mutableStateOf(false) }
    if (manualDialog) ManualBackupDialog(onSaveWithPassword) { manualDialog = false }
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopActions(onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.signing_key_title),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.signing_key_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            KeyCard(state)
            state.certificateSha256?.let {
                Text(
                    if (state.autoBackupFile != null) {
                        stringResource(R.string.key_backup_auto_present, state.autoBackupFile)
                    } else {
                        stringResource(R.string.key_backup_auto_missing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            state.message?.let { MessageCard(it, onDismiss = { onEvent(SigningKeyEvent.DismissMessage) }) }
            Text(
                stringResource(R.string.key_backup_manual_heading),
                style = MaterialTheme.typography.titleSmall,
                color = colors.primary,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedAction(
                stringResource(R.string.signing_key_export),
                { onEvent(SigningKeyEvent.OpenExport) },
                Icons.Rounded.SaveAlt,
                enabled = state.keyExists && !state.unreadable && !state.busy,
            )
            OutlinedAction(
                stringResource(R.string.key_backup_with_password_entry),
                { manualDialog = true },
                Icons.Rounded.SaveAlt,
                enabled = state.keyExists && !state.unreadable && !state.busy,
            )
            if (!state.keyExists && !state.loading) FooterHint(stringResource(R.string.signing_key_export_needs_key))
            OutlinedAction(
                stringResource(R.string.signing_key_import),
                { onEvent(SigningKeyEvent.OpenImport) },
                Icons.Rounded.FileOpen,
            )
            state.localBackup?.let { LocalBackupCard(it, state.busy, onRevealLocalPassword) { onEvent(SigningKeyEvent.ExportLocalBackup) } }
            when (state.form) {
                SigningKeyForm.Export -> ExportForm(state, onEvent)
                SigningKeyForm.Import -> ImportForm(state, onEvent, onPickSavedPassword)
                SigningKeyForm.None -> Unit
            }
        }
    }
    state.confirmation?.let { ConfirmDialog(it, state.savedPasswordMismatch, onEvent) }
}

/** The private copy in app storage: its password only on demand (and with screenshots blocked), and the file for export. */
@Composable
private fun LocalBackupCard(file: String, busy: Boolean, reveal: suspend () -> CharArray?, onExport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Deliberately remember, not rememberSaveable: the password never reaches saved instance state.
    var password by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val window = LocalActivity.current?.window
    DisposableEffect(password != null) {
        if (password != null) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (password != null) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    FormCard {
        Text(stringResource(R.string.local_backup_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.key_backup_private_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text(file, style = PatcherTheme.text.mono)
        password?.let { Text(it, style = PatcherTheme.text.mono) }
        OutlinedAction(
            stringResource(if (password == null) R.string.local_backup_show_password else R.string.signing_key_hide_password),
            {
                if (password != null) {
                    password = null
                } else {
                    scope.launch {
                        val chars = reveal()
                        if (chars != null) {
                            password = String(chars)
                            chars.fill('\u0000')
                        }
                    }
                }
            },
            if (password == null) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            enabled = !busy,
        )
        OutlinedAction(stringResource(R.string.local_backup_export), onExport, Icons.Rounded.SaveAlt, enabled = !busy)
    }
}

@Composable
private fun KeyCard(state: SigningKeyUiState) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(28.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Key, contentDescription = null)
                Text(
                    stringResource(if (state.keyExists) R.string.signing_key_present else R.string.signing_key_absent),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    modifier = Modifier.weight(1f),
                )
                if (state.certificateSha256 != null) {
                    InfoChip(
                        stringResource(if (state.backedUp) R.string.signing_key_backed_up else R.string.signing_key_not_backed_up),
                        containerColor = if (state.backedUp) colors.primary else colors.tertiaryContainer,
                        contentColor = if (state.backedUp) colors.onPrimary else colors.onTertiaryContainer,
                    )
                }
            }
            val sha = state.certificateSha256
            when {
                sha != null -> {
                    Text(stringResource(R.string.signing_key_sha_label), style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            formatDigest(sha),
                            style = PatcherTheme.text.mono,
                            modifier = Modifier.weight(1f),
                        )
                        IconAction(
                            Icons.Rounded.ContentCopy,
                            stringResource(R.string.signing_key_copy),
                            {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SHA-256", formatDigest(sha))))
                                }
                            },
                            tint = colors.onPrimaryContainer,
                        )
                    }
                }
                state.unreadable -> Text(stringResource(R.string.signing_key_unreadable), style = MaterialTheme.typography.bodyMedium)
                !state.loading -> Text(stringResource(R.string.signing_key_absent_note), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MessageCard(message: SigningKeyMessage, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (message.isError) colors.errorContainer else colors.secondaryContainer,
        contentColor = if (message.isError) colors.onErrorContainer else colors.onSecondaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(message.text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun FormCard(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

// The typed passwords live in this composition only (not rememberSaveable), so they never reach a saved Bundle.
@Composable
private fun ExportForm(state: SigningKeyUiState, onEvent: (SigningKeyEvent) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val mismatch = state.formError == R.string.signing_key_error_mismatch
    FormCard {
        Text(stringResource(R.string.signing_key_export_hint), style = MaterialTheme.typography.bodyMedium)
        PasswordField(
            password, { password = it }, stringResource(R.string.signing_key_password), visible, { visible = !visible },
            error = if (mismatch) null else state.formError,
            args = ExportPasswordPolicy.MIN_LENGTH,
        )
        PasswordField(
            confirm, { confirm = it }, stringResource(R.string.signing_key_password_repeat), visible, { visible = !visible },
            error = if (mismatch) state.formError else null,
            toggle = false,
        )
        PrimaryAction(
            stringResource(R.string.signing_key_choose_location),
            { onEvent(SigningKeyEvent.SubmitExport(password.toCharArray(), confirm.toCharArray())) },
            enabled = !state.busy,
        )
        QuietAction(stringResource(R.string.action_cancel), { onEvent(SigningKeyEvent.CloseForm) })
    }
}

@Composable
private fun ImportForm(state: SigningKeyUiState, onEvent: (SigningKeyEvent) -> Unit, onPickSavedPassword: suspend () -> PickedPassword?) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var noSaved by remember { mutableStateOf(false) }
    var savedId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    FormCard {
        Text(stringResource(R.string.signing_key_import_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedAction(
            stringResource(R.string.key_backup_use_saved),
            {
                scope.launch {
                    val saved = onPickSavedPassword()
                    noSaved = saved == null
                    if (saved != null) {
                        password = String(saved.password)
                        saved.password.fill('\u0000')
                        savedId = saved.credentialId
                    }
                }
            },
            Icons.Rounded.Key,
            enabled = !state.busy,
        )
        if (noSaved) Text(stringResource(R.string.key_backup_use_saved_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        PasswordField(
            // Typing a different password makes the picked credential irrelevant.
            password, { password = it; savedId = null }, stringResource(R.string.signing_key_password), visible, { visible = !visible },
            error = state.formError,
        )
        if (state.savedPasswordMismatch) Text(stringResource(R.string.key_backup_wrong_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        PrimaryAction(
            stringResource(R.string.signing_key_choose_file),
            { onEvent(SigningKeyEvent.SubmitImport(password.toCharArray(), savedId)) },
            enabled = !state.busy,
        )
        QuietAction(stringResource(R.string.action_cancel), { onEvent(SigningKeyEvent.CloseForm) })
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggle: () -> Unit,
    error: Int?,
    modifier: Modifier = Modifier,
    toggle: Boolean = true,
    args: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { res ->
            { Text(if (args != null) stringResource(res, args) else stringResource(res)) }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = if (toggle) {
            {
                IconAction(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    stringResource(if (visible) R.string.signing_key_hide_password else R.string.signing_key_show_password),
                    onToggle,
                )
            }
        } else {
            null
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConfirmDialog(confirmation: ImportConfirmation, savedPasswordMismatch: Boolean, onEvent: (SigningKeyEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(SigningKeyEvent.DismissConfirmation) },
        icon = { Icon(Icons.Rounded.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.signing_key_confirm_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.signing_key_confirm_warning), style = MaterialTheme.typography.bodyMedium)
                if (savedPasswordMismatch) Text(stringResource(R.string.key_backup_wrong_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                DigestBlock(stringResource(R.string.signing_key_confirm_current), confirmation.currentSha256)
                DigestBlock(stringResource(R.string.signing_key_confirm_new), confirmation.importedSha256)
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SigningKeyEvent.ConfirmImport) }) {
                Text(stringResource(R.string.signing_key_confirm_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SigningKeyEvent.DismissConfirmation) }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DigestBlock(label: String, sha256: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            sha256?.let(::formatDigest) ?: stringResource(R.string.signing_key_confirm_none),
            style = PatcherTheme.text.mono,
        )
    }
}
