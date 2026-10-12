package io.openshm.patcher.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.R
import io.openshm.patcher.data.AppUpdate
import io.openshm.patcher.data.AppUpdateError

/** The self-update dialog, drawn above whatever screen is showing; nothing while [state] is idle. */
@Composable
fun AppUpdateDialog(
    state: AppUpdateUiState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    when (state) {
        AppUpdateUiState.Idle -> Unit
        is AppUpdateUiState.Available -> OfferDialog(
            state.update,
            body = { Notes(state.update) },
            confirm = DialogButton(R.string.update_action_update, onUpdate),
            dismiss = DialogButton(R.string.update_action_later, onLater),
            onDismissRequest = onLater,
        )
        is AppUpdateUiState.NeedsPermission -> OfferDialog(
            state.update,
            body = { Text(stringResource(R.string.update_permission_text)) },
            confirm = DialogButton(R.string.action_continue, onUpdate),
            dismiss = DialogButton(R.string.action_cancel, onClose),
            onDismissRequest = onClose,
        )
        is AppUpdateUiState.Downloading -> OfferDialog(
            state.update,
            body = { Progress(R.string.update_downloading, state.progress) },
            confirm = null,
            dismiss = DialogButton(R.string.action_cancel, onCancel),
            onDismissRequest = {},
        )
        is AppUpdateUiState.Verifying -> OfferDialog(
            state.update,
            body = { Progress(R.string.update_verifying, null) },
            confirm = null,
            dismiss = DialogButton(R.string.action_cancel, onCancel),
            onDismissRequest = {},
        )
        is AppUpdateUiState.Installing -> OfferDialog(
            state.update,
            body = {
                Progress(R.string.update_installing, state.progress)
                Text(stringResource(R.string.update_closing_note), style = MaterialTheme.typography.bodyMedium)
            },
            confirm = null,
            dismiss = null,
            onDismissRequest = {},
        )
        is AppUpdateUiState.Error -> OfferDialog(
            state.update,
            body = { Text(errorText(state.error, state.detail)) },
            confirm = DialogButton(R.string.action_retry, onUpdate),
            dismiss = DialogButton(R.string.action_close, onClose),
            onDismissRequest = onClose,
        )
    }
}

private class DialogButton(val label: Int, val onClick: () -> Unit)

@Composable
private fun OfferDialog(
    update: AppUpdate,
    body: @Composable () -> Unit,
    confirm: DialogButton?,
    dismiss: DialogButton?,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_dialog_body, update.build, update.versionName, BuildConfig.VERSION_CODE))
                body()
            }
        },
        confirmButton = { confirm?.let { TextButton(it.onClick) { Text(stringResource(it.label)) } } },
        dismissButton = { dismiss?.let { TextButton(it.onClick) { Text(stringResource(it.label)) } } },
    )
}

/** The release notes as plain text, collapsed until asked for. */
@Composable
private fun Notes(update: AppUpdate) {
    if (update.notes.isBlank()) {
        Text(stringResource(R.string.update_closing_note), style = MaterialTheme.typography.bodyMedium)
        return
    }
    var shown by rememberSaveable { mutableStateOf(false) }
    TextButton({ shown = !shown }) {
        Text(stringResource(if (shown) R.string.update_notes_hide else R.string.update_notes_show))
    }
    if (shown) {
        Text(
            update.notes,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        )
    }
    Text(stringResource(R.string.update_closing_note), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Progress(label: Int, progress: Float?) {
    Text(stringResource(label))
    if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun errorText(error: AppUpdateError, detail: String?): String = when (error) {
    AppUpdateError.DownloadFailed -> stringResource(R.string.update_error_download)
    AppUpdateError.TooLarge -> stringResource(R.string.update_error_too_large)
    AppUpdateError.HashMismatch -> stringResource(R.string.update_error_hash)
    AppUpdateError.NotThisApp -> stringResource(R.string.update_error_not_this_app)
    AppUpdateError.WrongBuild -> stringResource(R.string.update_error_wrong_build)
    AppUpdateError.OtherSigner -> stringResource(R.string.update_error_other_signer)
    AppUpdateError.Unreadable -> stringResource(R.string.update_error_unreadable)
    AppUpdateError.InstallFailed ->
        if (detail.isNullOrBlank()) stringResource(R.string.update_error_install) else stringResource(R.string.update_error_install_detail, detail)
}
