package io.openshm.patcher.ui.screens.welcome

import androidx.compose.foundation.background
import androidx.annotation.StringRes
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RingVolume
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.StockApp
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.WelcomeHero

/** One app the build installs, with the access it is given afterwards (`catalogue.json` `grants`). */
data class AppChange(val outputId: String, val label: String, val packageName: String, val grants: List<Grant>)

/** What the build changes on one device, from the verified catalogue. */
data class DeviceChanges(val device: Device, val apps: List<AppChange>, val disables: List<StockApp>)

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
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // The app's own header instead of a system title bar: the name set large, settings at the end.
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconAction(Icons.Rounded.Settings, stringResource(R.string.action_settings), onSettings)
            }
            WelcomeHero(Modifier.fillMaxWidth().height(220.dp))
            Text(
                stringResource(R.string.welcome_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
            )
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        ChangesContent(changes)
    }
}

/** The sheet's content: per device, each new app with what it is for and what it is allowed to do. */
@Composable
internal fun ChangesContent(changes: List<DeviceChanges>, modifier: Modifier = Modifier) {
    var technical by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.changes_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.changes_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .toggleable(technical, role = Role.Switch, onValueChange = { technical = it })
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.changes_technical), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(technical, onCheckedChange = null)
        }
        changes.filter { it.apps.isNotEmpty() || it.disables.isNotEmpty() }.forEach { DeviceSection(it, technical) }
    }
}

@Composable
private fun DeviceSection(changes: DeviceChanges, technical: Boolean) {
    val phone = changes.device == Device.Phone
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(horizontal = 8.dp).semantics(mergeDescendants = true) { heading() },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Watch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                stringResource(if (phone) R.string.changes_on_phone else R.string.changes_on_watch),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        changes.apps.forEach { AppCard(it, technical) }
        changes.disables.forEach { DisableCard(it, technical) }
    }
}

@Composable
private fun AppCard(app: AppChange, technical: Boolean) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(appIcon(app.outputId), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    outputPurpose(app.outputId)?.let {
                        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (technical) PackageId(app.packageName, maxLines = 2)
                }
            }
            if (app.grants.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    stringResource(R.string.changes_allowed_to),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                app.grants.forEach { GrantRow(app.outputId, it, technical) }
            }
        }
    }
}

@Composable
private fun GrantRow(outputId: String, grant: Grant, technical: Boolean) {
    val title = grantTitle(grant)
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(
            grantIcon(title),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 10.dp, top = 2.dp).size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                if (grant.optional) OptionalBadge()
            }
            val reason = grantReason(outputId, grant)
            if (reason != null) {
                Text(stringResource(reason), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (technical || reason == null) PackageId(grantTechnicalName(grant), maxLines = 2)
        }
    }
}

@Composable
private fun OptionalBadge() {
    Text(
        stringResource(R.string.changes_optional),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DisableCard(stock: StockApp, technical: Boolean) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.changes_disables),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (stock.optional) OptionalBadge()
                }
                Text(stock.label, style = MaterialTheme.typography.titleMedium)
                Text(stock.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.changes_disable_undo), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (technical) PackageId(stock.packageName, maxLines = 2)
            }
        }
    }
}

private fun appIcon(outputId: String): ImageVector = when (outputId) {
    "shm-phone", "shm-watch" -> Icons.Rounded.MonitorHeart
    "alarm-fork" -> Icons.Rounded.Alarm
    else -> Icons.Rounded.SwapHoriz
}

private fun grantIcon(@StringRes title: Int): ImageVector = when (title) {
    R.string.grant_title_calls -> Icons.Rounded.Call
    R.string.grant_title_phone_state -> Icons.Rounded.RingVolume
    R.string.grant_title_notifications -> Icons.Rounded.Notifications
    R.string.grant_title_bluetooth -> Icons.Rounded.Bluetooth
    R.string.grant_title_restricted -> Icons.Rounded.LockOpen
    R.string.grant_title_overlay -> Icons.Rounded.Layers
    R.string.grant_title_listener -> Icons.Rounded.NotificationsActive
    R.string.grant_title_exact_alarm -> Icons.Rounded.Alarm
    R.string.grant_title_full_screen -> Icons.Rounded.Fullscreen
    R.string.grant_title_ongoing_calls -> Icons.Rounded.PhoneInTalk
    R.string.grant_title_always_on -> Icons.Rounded.Bedtime
    R.string.grant_title_battery -> Icons.Rounded.BatteryFull
    else -> Icons.Rounded.Key
}
