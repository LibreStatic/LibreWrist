package io.openshm.patcher.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.TopActions
import io.openshm.patcher.ui.update.UpdateNotice

/** App settings: the update check, the signing key and the installed version. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    notice: UpdateNotice?,
    onNoticeShown: () -> Unit,
    onAutoCheck: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onSigningKey: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    val upToDate = stringResource(R.string.settings_up_to_date)
    val failed = stringResource(R.string.settings_check_failed)
    LaunchedEffect(notice) {
        val text = when (notice) {
            UpdateNotice.UpToDate -> upToDate
            UpdateNotice.CheckFailed -> failed
            null -> return@LaunchedEffect
        }
        onNoticeShown()
        snackbar.showSnackbar(text)
    }
    Box(modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.fillMaxSize()) {
            TopActions(onBack = onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.action_settings),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.settings_updates_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .toggleable(state.autoCheck, enabled = state.updatesAvailable, role = Role.Switch, onValueChange = onAutoCheck)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_auto_check),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.updatesAvailable) colors.onSurface else colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(state.autoCheck && state.updatesAvailable, onCheckedChange = null, enabled = state.updatesAvailable)
                }
                Text(
                    stringResource(if (state.updatesAvailable) R.string.settings_updates_explainer else R.string.settings_updates_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                OutlinedAction(
                    stringResource(if (state.checking) R.string.settings_checking else R.string.settings_check_now),
                    onCheckNow,
                    Icons.Rounded.Update,
                    enabled = state.updatesAvailable && !state.checking,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onSigningKey)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.Key, contentDescription = null, tint = colors.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_menu_signing_key), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_signing_key_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_version_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
                Text(
                    stringResource(R.string.settings_version_value, state.versionName, state.versionCode),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}
