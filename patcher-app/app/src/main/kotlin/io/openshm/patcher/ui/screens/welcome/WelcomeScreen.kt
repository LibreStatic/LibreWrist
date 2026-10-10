package io.openshm.patcher.ui.screens.welcome

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.WelcomeHero

/** One line of the "what will change" sheet. [detail] is a package id or a permission name. */
data class ChangeLine(val title: String, val detail: String? = null)

/** What the build changes on one device, from the verified catalogue. */
data class DeviceChanges(
    val device: Device,
    val installs: List<ChangeLine>,
    val grants: List<ChangeLine>,
    val disables: List<ChangeLine>,
)

data class WelcomeUiState(
    val changesSheetVisible: Boolean = false,
    val changes: List<DeviceChanges> = emptyList(),
)

sealed interface WelcomeEvent {
    data object ShowChanges : WelcomeEvent
    data object HideChanges : WelcomeEvent
}

@Composable
fun WelcomeScreen(
    state: WelcomeUiState,
    onEvent: (WelcomeEvent) -> Unit,
    onGetStarted: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconAction(Icons.Rounded.Settings, stringResource(R.string.action_settings), onSettings)
            }
            WelcomeHero(Modifier.fillMaxWidth().height(300.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.semantics { heading() },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Promise(Icons.Rounded.Download, R.string.welcome_promise_download,
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                Promise(Icons.Rounded.Tune, R.string.welcome_promise_patch,
                    MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                Promise(Icons.Rounded.Undo, R.string.welcome_promise_undo,
                    MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryAction(stringResource(R.string.welcome_get_started), onGetStarted)
            QuietAction(stringResource(R.string.welcome_what_changes), { onEvent(WelcomeEvent.ShowChanges) })
        }
    }

    if (state.changesSheetVisible) {
        ChangesSheet(state.changes, onDismiss = { onEvent(WelcomeEvent.HideChanges) })
    }
}

@Composable
private fun Promise(icon: ImageVector, textRes: Int, container: Color, content: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = content)
        }
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangesSheet(changes: List<DeviceChanges>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.changes_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.changes_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            changes.forEach { DeviceChangesBlock(it) }
            Box(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DeviceChangesBlock(changes: DeviceChanges) {
    val deviceTitle = stringResource(if (changes.device == Device.Phone) R.string.device_phone else R.string.device_watch)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(deviceTitle)
        ChangeGroup(R.string.changes_installs, changes.installs)
        ChangeGroup(R.string.changes_grants, changes.grants)
        ChangeGroup(R.string.changes_disables, changes.disables)
    }
}

@Composable
private fun ChangeGroup(titleRes: Int, lines: List<ChangeLine>) {
    if (lines.isEmpty()) return
    Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
    lines.forEach { line ->
        Column(Modifier.padding(start = 8.dp, bottom = 2.dp)) {
            Text(line.title, style = MaterialTheme.typography.bodyMedium)
            line.detail?.let { PackageId(it) }
        }
    }
}
