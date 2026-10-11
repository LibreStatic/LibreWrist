package io.openshm.patcher.ui.screens.build

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import io.openshm.patcher.R
import io.openshm.patcher.ui.screens.signingkey.ExportPasswordPolicy
import kotlinx.coroutines.launch

/**
 * Asks for a password and saves the key to Documents/OpenSHM with it. [onSave] gets two char arrays
 * that this dialog wipes once it returns, and tells whether the file was saved. The typed text lives in
 * `remember` (never saved instance state) and the window is secure while the dialog is shown.
 */
@Composable
fun ManualBackupDialog(onSave: suspend (CharArray, CharArray) -> Boolean, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activityWindow = LocalActivity.current?.window
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(Unit) {
        activityWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            activityWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    val transformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.key_backup_with_password_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.key_backup_with_password_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    password, { password = it }, Modifier, label = { Text(stringResource(R.string.signing_key_password)) },
                    singleLine = true, visualTransformation = transformation, keyboardOptions = keyboard,
                    isError = error == R.string.signing_key_error_short,
                    supportingText = if (error == R.string.signing_key_error_short) {
                        { Text(stringResource(R.string.signing_key_error_short, ExportPasswordPolicy.MIN_LENGTH)) }
                    } else null,
                )
                OutlinedTextField(
                    confirm, { confirm = it }, Modifier, label = { Text(stringResource(R.string.signing_key_password_repeat)) },
                    singleLine = true, visualTransformation = transformation, keyboardOptions = keyboard,
                    isError = error == R.string.signing_key_error_mismatch,
                    supportingText = if (error == R.string.signing_key_error_mismatch) {
                        { Text(stringResource(R.string.signing_key_error_mismatch)) }
                    } else null,
                )
                if (error == R.string.signing_key_export_failed) {
                    Text(stringResource(R.string.signing_key_export_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { visible = !visible }) {
                    Text(stringResource(if (visible) R.string.signing_key_hide_password else R.string.signing_key_show_password))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val p = password.toCharArray()
                    val c = confirm.toCharArray()
                    val problem = ExportPasswordPolicy.problem(p, c)
                    if (problem != null) {
                        p.fill('\u0000')
                        c.fill('\u0000')
                        error = problem
                        return@TextButton
                    }
                    error = null
                    busy = true
                    scope.launch {
                        val ok = try {
                            onSave(p, c)
                        } finally {
                            p.fill('\u0000')
                            c.fill('\u0000')
                            busy = false
                        }
                        if (ok) onDismiss() else error = R.string.signing_key_export_failed
                    }
                },
            ) { Text(stringResource(R.string.key_backup_with_password_action)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
