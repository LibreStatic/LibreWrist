package io.openshm.patcher.ui.screens.build

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.components.CheckBlob
import io.openshm.patcher.ui.components.DecorShape
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.TopActions

/** One produced app: its label, the final package name (prefix already applied) and size. */
data class BuiltOutput(val id: String, val label: String, val device: Device, val packageName: String, val sizeBytes: Long)

data class BuildDoneUiState(
    val outputs: List<BuiltOutput> = emptyList(),
    val patchCount: Int = 0,
    val backup: KeyBackupStatus = KeyBackupStatus.Hidden,
)

/** The automatic signing key backup as the done screen shows it. */
enum class KeyBackupStatus {
    /** Nothing to say (no key yet, or still working out whether a backup is due). */
    Hidden,
    Saving,
    Saved,

    /** The user declined the password manager and only the private copy in app storage exists; a button retries it. */
    PrivateOnly,

    /** No password manager is available and only the private copy exists; a typed password saves to Documents instead. */
    PrivateNoPasswordManager,

    /** The user cancelled the password manager and no copy exists: no automatic prompt again, a button saves on demand. */
    Declined,

    /** Not saved automatically; the manual way is offered. */
    Failed,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildDoneScreen(
    state: BuildDoneUiState,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onBackUpKey: () -> Unit = {},
    onSaveBackup: () -> Unit = {},
    onSaveWithPassword: suspend (CharArray, CharArray) -> Boolean = { _, _ -> false },
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopActions(onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                DecorShape(MaterialShapes.Sunny, 36.dp, colors.secondaryContainer, Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 12.dp))
                DecorShape(MaterialShapes.Pill, 28.dp, colors.primaryContainer, Modifier.align(Alignment.BottomEnd).padding(end = 44.dp, bottom = 8.dp), rotation = 30f)
                DecorShape(MaterialShapes.Diamond, 24.dp, colors.tertiaryContainer, Modifier.align(Alignment.TopEnd).padding(end = 52.dp, top = 24.dp))
                CheckBlob(200.dp, colors.primary, colors.onPrimary, description = stringResource(R.string.build_done_title))
            }
            Text(
                stringResource(R.string.build_done_title),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight(850)),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                pluralStringResource(R.plurals.build_done_subtitle_apps, state.outputs.size, state.outputs.size) +
                    " · " + pluralStringResource(R.plurals.build_done_subtitle_patches, state.patchCount, state.patchCount) +
                    " · " + stringResource(R.string.build_done_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = GroupedSpacing) {
                state.outputs.forEachIndexed { index, out ->
                    GroupedItem(index, state.outputs.size, outerRadius = 32) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(out.label, style = MaterialTheme.typography.titleSmall)
                                PackageId(out.packageName)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                InfoChip(stringResource(if (out.device == Device.Phone) R.string.device_phone else R.string.device_watch))
                                if (out.sizeBytes > 0) {
                                    Text(
                                        stringResource(R.string.size_mb, out.sizeBytes / (1024.0 * 1024.0)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            NoteRow(Icons.Rounded.Link, stringResource(R.string.build_done_link_note))
            KeyBackupCard(state.backup, onBackUpKey, onSaveBackup, onSaveWithPassword)
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            PrimaryAction(stringResource(R.string.build_done_install), onInstall)
        }
    }
}

/** Status of the automatic signing key backup; when it failed, asks for the manual one (losing the key means installed apps can only be reinstalled). */
@Composable
private fun KeyBackupCard(
    status: KeyBackupStatus,
    onBackUp: () -> Unit,
    onSave: () -> Unit,
    onSaveWithPassword: suspend (CharArray, CharArray) -> Boolean,
) {
    var dialog by remember { mutableStateOf(false) }
    if (dialog) ManualBackupDialog(onSaveWithPassword) { dialog = false }
    val colors = MaterialTheme.colorScheme
    if (status == KeyBackupStatus.Hidden) return
    val failed = status == KeyBackupStatus.Failed
    val declined = status == KeyBackupStatus.Declined || status == KeyBackupStatus.PrivateOnly
    val noManager = status == KeyBackupStatus.PrivateNoPasswordManager
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (failed || declined || noManager) colors.tertiaryContainer else colors.secondaryContainer,
        contentColor = if (failed || declined || noManager) colors.onTertiaryContainer else colors.onSecondaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status == KeyBackupStatus.Saving) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                Icon(if (failed || declined || noManager) Icons.Rounded.Key else Icons.Rounded.CheckCircle, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                when (status) {
                    KeyBackupStatus.Saving -> {
                        Text(stringResource(R.string.key_backup_saving), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.key_backup_consent), style = MaterialTheme.typography.bodySmall)
                    }
                    KeyBackupStatus.PrivateOnly, KeyBackupStatus.PrivateNoPasswordManager -> {
                        Text(stringResource(R.string.key_backup_private_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (noManager) R.string.key_backup_no_manager_hint else R.string.key_backup_private_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    KeyBackupStatus.Declined -> {
                        Text(stringResource(R.string.key_backup_declined), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.key_backup_consent), style = MaterialTheme.typography.bodySmall)
                    }
                    KeyBackupStatus.Saved -> Text(stringResource(R.string.key_backup_saved), style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(stringResource(R.string.key_backup_failed), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.signing_key_nudge_body), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (noManager) {
                Button(onClick = { dialog = true }) { Text(stringResource(R.string.key_backup_with_password_action)) }
            } else if (declined) {
                Button(onClick = onSave) { Text(stringResource(R.string.key_backup_save_action)) }
            } else if (failed) {
                Button(
                    onClick = onBackUp,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
                ) { Text(stringResource(R.string.signing_key_nudge_action)) }
            }
        }
    }
}
