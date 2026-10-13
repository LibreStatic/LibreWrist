package io.openshm.patcher.ui.screens.devicelogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openshm.patcher.R
import io.openshm.patcher.device.DisconnectReason
import io.openshm.patcher.device.DeviceLogState
import io.openshm.patcher.device.LogDevice
import io.openshm.patcher.device.LogLinkStatus
import io.openshm.patcher.ui.components.Accent
import io.openshm.patcher.ui.components.ActionRow
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.TopActions
import io.openshm.patcher.ui.theme.PatcherTheme
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceLogsScreen(
    state: DeviceLogsUiState,
    onEvent: (DeviceLogsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val selected = if (state.tab == LogDevice.Watch) state.logs.watch else state.logs.phone
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let {
        stringResource(if (it == DeviceLogsMessage.Exported) R.string.device_logs_exported else R.string.device_logs_export_failed)
    }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            onEvent(DeviceLogsEvent.DismissMessage)
        }
    }
    Box(modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopActions(onBack = onBack)
            Text(
                stringResource(R.string.device_logs_title),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).semantics { heading() },
            )
            PrimaryTabRow(selectedTabIndex = LogDevice.entries.indexOf(state.tab), containerColor = Color.Transparent) {
                LogDevice.entries.forEach { device ->
                    val log = if (device == LogDevice.Watch) state.logs.watch else state.logs.phone
                    Tab(
                        selected = device == state.tab,
                        onClick = { onEvent(DeviceLogsEvent.SelectTab(device)) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LinkIcon(log.status, Modifier.size(18.dp))
                                Text(stringResource(if (device == LogDevice.Watch) R.string.device_watch else R.string.device_phone))
                            }
                        },
                    )
                }
            }
            StatusRow(selected, state.logs.capturing, onTap = { onEvent(DeviceLogsEvent.ShowHelp) })
            Packages(selected)
            LogBody(selected, Modifier.weight(1f))
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                ActionRow {
                    PrimaryAction(
                        stringResource(R.string.device_logs_export),
                        { onEvent(DeviceLogsEvent.Export) },
                        icon = Icons.Rounded.SaveAlt,
                    )
                    OutlinedAction(
                        stringResource(if (state.logs.capturing) R.string.device_logs_stop else R.string.device_logs_resume),
                        { onEvent(DeviceLogsEvent.ToggleCapture) },
                        if (state.logs.capturing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    )
                }
                QuietAction(stringResource(R.string.device_logs_clear), { onEvent(DeviceLogsEvent.Clear) })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
    val status = selected.status
    if (state.helpOpen && status is LogLinkStatus.Disconnected) {
        HelpSheet(state.tab, status.reason, onEvent)
    }
}

/** The link icon of a tab: success when streaming, error when not connected, warning while (re)connecting. */
@Composable
private fun LinkIcon(status: LogLinkStatus, modifier: Modifier = Modifier) {
    val palette = PatcherTheme.status
    val (icon, tint) = when (status) {
        LogLinkStatus.Streaming -> Icons.Rounded.Link to palette.success
        LogLinkStatus.Connecting, LogLinkStatus.Reconnecting -> Icons.Rounded.Link to palette.warning
        is LogLinkStatus.Disconnected, LogLinkStatus.Idle -> Icons.Rounded.LinkOff to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}

@Composable
private fun StatusRow(log: DeviceLogState, capturing: Boolean, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = PatcherTheme.status
    val status = log.status
    val container: Color
    val content: Color
    when (status) {
        LogLinkStatus.Streaming -> { container = palette.successContainer; content = palette.onSuccessContainer }
        LogLinkStatus.Connecting, LogLinkStatus.Reconnecting -> { container = palette.warningContainer; content = palette.onWarningContainer }
        is LogLinkStatus.Disconnected -> { container = colors.errorContainer; content = colors.onErrorContainer }
        LogLinkStatus.Idle -> { container = colors.surfaceContainerHigh; content = colors.onSurfaceVariant }
    }
    val title = stringResource(
        when {
            status is LogLinkStatus.Streaming -> R.string.device_logs_status_streaming
            status is LogLinkStatus.Disconnected -> R.string.device_logs_status_disconnected
            status is LogLinkStatus.Connecting -> R.string.device_logs_status_connecting
            status is LogLinkStatus.Reconnecting -> R.string.device_logs_status_reconnecting
            else -> R.string.device_logs_status_idle
        },
    )
    val detail = listOfNotNull(
        if (status is LogLinkStatus.Streaming || log.lineCount > 0) stringResource(R.string.device_logs_lines, log.lineCount.toInt()) else null,
        log.deviceLabel,
    ).joinToString(" · ")
    val shape = RoundedCornerShape(16.dp)
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    val body: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (status) {
                LogLinkStatus.Connecting, LogLinkStatus.Reconnecting ->
                    CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp)
                LogLinkStatus.Streaming -> Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (status is LogLinkStatus.Disconnected) {
        Surface(onClick = onTap, modifier = modifier, shape = shape, color = container, contentColor = content, content = body)
    } else {
        Surface(modifier = modifier, shape = shape, color = container, contentColor = content, content = body)
    }
}

@Composable
private fun Packages(log: DeviceLogState) {
    if (log.packages.isNotEmpty()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(log.packages) { InfoChip(it) }
        }
    } else if (log.status is LogLinkStatus.Streaming) {
        Text(
            stringResource(R.string.device_logs_no_apps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The tail of the log in monospace; it follows new lines while the user is at the bottom and pauses when they scroll up. */
@Composable
private fun LogBody(log: DeviceLogState, modifier: Modifier = Modifier) {
    val lines = log.tail
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { follow = atBottom }
    }
    LaunchedEffect(log.device, log.lineCount, lines.size) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.device_logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(8.dp)) {
                items(lines.size) { index ->
                    Text(
                        lines[index],
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** What to do about a disconnected device, per device and reason. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HelpSheet(device: LogDevice, reason: DisconnectReason, onEvent: (DeviceLogsEvent) -> Unit) {
    val watch = device == LogDevice.Watch
    val untrusted = reason == DisconnectReason.KeyNotTrusted || (!watch && reason == DisconnectReason.NotPaired)
    val unpairedWatch = watch && (reason == DisconnectReason.NotPaired || reason == DisconnectReason.KeyNotTrusted)
    ModalBottomSheet(
        onDismissRequest = { onEvent(DeviceLogsEvent.HideHelp) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (watch) R.string.device_logs_sheet_watch_title else R.string.device_logs_sheet_phone_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(
                    when {
                        unpairedWatch -> R.string.device_logs_sheet_watch_not_paired
                        watch -> R.string.device_logs_sheet_watch_off
                        untrusted -> R.string.device_logs_sheet_phone_untrusted
                        else -> R.string.device_logs_sheet_phone_off
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ActionRow {
                when {
                    unpairedWatch -> PrimaryAction(
                        stringResource(R.string.device_logs_sheet_watch_pair),
                        { onEvent(DeviceLogsEvent.OpenWatchPair) },
                        accent = Accent.Tertiary,
                    )
                    watch -> {
                        PrimaryAction(stringResource(R.string.action_retry), { onEvent(DeviceLogsEvent.Retry) }, icon = Icons.Rounded.Refresh, accent = Accent.Tertiary)
                    }
                    untrusted -> {
                        PrimaryAction(stringResource(R.string.device_logs_sheet_phone_pair), { onEvent(DeviceLogsEvent.PairPhone) })
                        OutlinedAction(stringResource(R.string.action_retry), { onEvent(DeviceLogsEvent.Retry) }, Icons.Rounded.Refresh)
                    }
                    else -> {
                        PrimaryAction(stringResource(R.string.device_logs_sheet_phone_open), { onEvent(DeviceLogsEvent.OpenDeveloperOptions) }, icon = Icons.Rounded.Settings)
                        OutlinedAction(stringResource(R.string.action_retry), { onEvent(DeviceLogsEvent.Retry) }, Icons.Rounded.Refresh)
                    }
                }
            }
        }
    }
}
