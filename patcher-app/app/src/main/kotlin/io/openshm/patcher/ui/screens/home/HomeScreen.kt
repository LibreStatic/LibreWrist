package io.openshm.patcher.ui.screens.home

import io.openshm.patcher.ui.components.ActionRow
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fitbit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PhoneWatchIllustration
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.StatusShape

/** Overall health from the last self-test; [daysAgo] null means it never ran. */
data class HomeStatus(val status: CheckStatus, val daysAgo: Int?)

/** [patchesOnly]: the input is the same, only the patches applied to it changed. */
data class UpdateOffer(val appLabel: String, val fromVersion: String, val toVersion: String, val patchesOnly: Boolean = false)

/** A line shown once after a catalogue check; [id] makes a repeated identical message show again. */
data class CatalogueMessage(@StringRes val textRes: Int, val serial: Long? = null, val id: Long = 0L)

/** [customizable] is false for an output with no patches to choose (a bridge): its customize action is off. */
data class InstalledBuild(val id: String, val label: String, val packageName: String, val device: Device, val customizable: Boolean = true)

data class HomeUiState(
    val status: HomeStatus = HomeStatus(CheckStatus.Pending, null),
    val update: UpdateOffer? = null,
    val builds: List<InstalledBuild> = emptyList(),
    val menuOpen: Boolean = false,
    /** The build the restore sheet asks about; null while the sheet is closed. */
    val restoreTarget: InstalledBuild? = null,
    val catalogueMessage: CatalogueMessage? = null,
    val checkingCatalogue: Boolean = false,
) {
    val restoreSheetVisible: Boolean get() = restoreTarget != null
}

sealed interface HomeEvent {
    data object TestAgain : HomeEvent
    data object StartUpdate : HomeEvent
    data class Rebuild(val buildId: String) : HomeEvent
    data class AskRestore(val buildId: String) : HomeEvent
    data object ConfirmRestore : HomeEvent
    data object DismissRestore : HomeEvent
    data object ToggleMenu : HomeEvent
    data object NewBuild : HomeEvent
    data object RunSelfTest : HomeEvent
    data object RestoreWatch : HomeEvent
    data object Uninstall : HomeEvent
    data object OpenSettings : HomeEvent
    data object CheckCatalogue : HomeEvent
    data object MessageShown : HomeEvent
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    Box(modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.home_title),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { heading() },
            )
            StatusCard(state.status, onEvent)
            state.update?.let { UpdateCard(it, onEvent) }
            if (state.builds.isNotEmpty()) {
                Column {
                    SectionTitle(stringResource(R.string.home_current_builds))
                    Column(verticalArrangement = GroupedSpacing) {
                        state.builds.forEachIndexed { index, build -> BuildRow(index, state.builds.size, build, onEvent) }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = { onEvent(HomeEvent.ToggleMenu) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new_build)) },
            )
            DropdownMenu(expanded = state.menuOpen, onDismissRequest = { onEvent(HomeEvent.ToggleMenu) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_new_build)) },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = { onEvent(HomeEvent.NewBuild) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_self_test)) },
                    leadingIcon = { Icon(Icons.Rounded.Fitbit, contentDescription = null) },
                    onClick = { onEvent(HomeEvent.RunSelfTest) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_check_catalogue)) },
                    leadingIcon = { Icon(Icons.Rounded.Update, contentDescription = null) },
                    enabled = !state.checkingCatalogue,
                    onClick = { onEvent(HomeEvent.CheckCatalogue) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_settings)) },
                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    onClick = { onEvent(HomeEvent.OpenSettings) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_restore_watch)) },
                    leadingIcon = { Icon(Icons.Rounded.SettingsBackupRestore, contentDescription = null) },
                    onClick = { onEvent(HomeEvent.RestoreWatch) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_menu_uninstall)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                    onClick = { onEvent(HomeEvent.Uninstall) },
                )
            }
        }
    }
    state.restoreTarget?.let { RestoreSheet(it, onEvent) }
    state.catalogueMessage?.let { message ->
        val text = if (message.serial != null) stringResource(message.textRes, message.serial) else stringResource(message.textRes)
        LaunchedEffect(message) {
            snackbar.showSnackbar(text)
            onEvent(HomeEvent.MessageShown)
        }
    }
}

@Composable
private fun StatusCard(status: HomeStatus, onEvent: (HomeEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(32.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PhoneWatchIllustration()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusShape(status.status, size = 32.dp)
                Column {
                    Text(
                        stringResource(
                            when (status.status) {
                                CheckStatus.Passed -> R.string.home_status_ok
                                CheckStatus.Warning -> R.string.home_status_warning
                                CheckStatus.Failed -> R.string.home_status_failed
                                else -> R.string.home_status_unknown
                            },
                        ),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    Text(
                        status.daysAgo?.let { pluralStringResource(R.plurals.home_tested_days_ago, it, it) }
                            ?: stringResource(R.string.home_never_tested),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Button(
                onClick = { onEvent(HomeEvent.TestAgain) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            ) { Text(stringResource(R.string.home_test_again)) }
        }
    }
}

@Composable
private fun UpdateCard(update: UpdateOffer, onEvent: (HomeEvent) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Update, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_update_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (update.patchesOnly) stringResource(R.string.home_update_patches, update.appLabel)
                    else stringResource(R.string.home_update_detail, update.appLabel, update.fromVersion, update.toVersion),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { onEvent(HomeEvent.StartUpdate) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary),
            ) { Text(stringResource(R.string.action_start)) }
        }
    }
}

@Composable
private fun BuildRow(index: Int, count: Int, build: InstalledBuild, onEvent: (HomeEvent) -> Unit) {
    GroupedItem(index, count) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(build.label, style = MaterialTheme.typography.titleSmall)
                PackageId(build.packageName)
                InfoChip(
                    stringResource(if (build.device == Device.Phone) R.string.device_phone else R.string.device_watch),
                    icon = if (build.device == Device.Phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Watch,
                )
            }
            IconAction(
                Icons.Rounded.Tune, stringResource(R.string.home_rebuild_description, build.label),
                { onEvent(HomeEvent.Rebuild(build.id)) },
                enabled = build.customizable,
            )
            IconAction(
                Icons.Rounded.SettingsBackupRestore, stringResource(R.string.home_restore_description, build.label),
                { onEvent(HomeEvent.AskRestore(build.id)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RestoreSheet(target: InstalledBuild, onEvent: (HomeEvent) -> Unit) {
    // An app that replaces a stock app or changed a watch setting: its restore sheet speaks of the whole watch.
    val watchMonitor = target.id in AppCatalogue.current.watchExtraOutputs
    ModalBottomSheet(
        onDismissRequest = { onEvent(HomeEvent.DismissRestore) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (watchMonitor) stringResource(R.string.restore_title) else stringResource(R.string.restore_app_title, target.label),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(
                    when {
                        watchMonitor -> R.string.restore_body
                        target.device == Device.Watch -> R.string.restore_app_body_watch
                        else -> R.string.restore_app_body_phone
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            ActionRow {
                PrimaryAction(stringResource(if (watchMonitor) R.string.restore_confirm else R.string.restore_app_confirm), { onEvent(HomeEvent.ConfirmRestore) })
                QuietAction(stringResource(R.string.action_cancel), { onEvent(HomeEvent.DismissRestore) })
            }
        }
    }
}
