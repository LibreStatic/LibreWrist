package io.openshm.patcher.ui.screens.welcome

import io.openshm.patcher.ui.components.ActionRow
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
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import io.openshm.patcher.ui.components.SinglePaneMaxWidth
import io.openshm.patcher.ui.components.TwoPaneMinWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.openshm.patcher.R
import io.openshm.patcher.data.AutomationState
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.Grant
import io.openshm.patcher.data.StockApp
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SubtleLink
import io.openshm.patcher.ui.components.WelcomeHero

/** One app the build installs, with the access it is given afterwards (`catalogue.json` `grants`). */
data class AppChange(
    val outputId: String,
    val label: String,
    val packageName: String,
    val grants: List<Grant>,
    /** One plain-language line on what the app is for, from the catalogue; empty when there is none. */
    val purpose: String = "",
    /** The catalogue `apps` icon symbol of the app this output belongs to. */
    val icon: String = "",
)

/** What the build changes on one device, from the verified catalogue. */
data class DeviceChanges(val device: Device, val apps: List<AppChange>, val disables: List<StockApp>)

data class WelcomeUiState(
    val changesSheetVisible: Boolean = false,
    val changes: List<DeviceChanges> = emptyList(),
    /** The dialog shown after "Get started" while steps that need nothing are passed over. */
    val skip: SkipDialog? = null,
    /** A device was paired over ADB, so the device logs can be opened. */
    val logsAvailable: Boolean = false,
    /** Something was installed by the setup, so everything can be undone. */
    val canRevert: Boolean = false,
    /** The automation switches shown under "Get started". */
    val automation: AutomationState = AutomationState(),
    /** The explanation dialog of a switch, while open. */
    val help: AutomationHelp? = null,
    /** The warning shown before "Advance freely" is turned on. */
    val freeRunWarning: Boolean = false,
    /**
     * The first-launch notice, shown only while an official app is missing: unattended mode only runs
     * end to end once the official apps were fetched by hand (and again after the list changes).
     */
    val appsNotice: Boolean = false,
)

sealed interface WelcomeEvent {
    data object ShowChanges : WelcomeEvent
    data object HideChanges : WelcomeEvent
    data object GetStarted : WelcomeEvent
    data object ConfirmSkip : WelcomeEvent
    data object CancelSkip : WelcomeEvent
    data object ManualSetup : WelcomeEvent
    data class SetGuidePet(val on: Boolean) : WelcomeEvent
    data class SetChinaMode(val on: Boolean) : WelcomeEvent
    data class SetUnattended(val on: Boolean) : WelcomeEvent
    data class SetFreeRun(val on: Boolean) : WelcomeEvent
    data class SetUninstallMismatch(val on: Boolean) : WelcomeEvent
    data class ShowHelp(val help: AutomationHelp) : WelcomeEvent
    data object HideHelp : WelcomeEvent
    data class ConfirmFreeRun(val dontShowAgain: Boolean) : WelcomeEvent
    data object CancelFreeRun : WelcomeEvent
    data object AcknowledgeAppsNotice : WelcomeEvent
}

@Composable
fun WelcomeScreen(
    state: WelcomeUiState,
    onEvent: (WelcomeEvent) -> Unit,
    onGetStarted: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onDeviceLogs: () -> Unit = {},
    onRevert: () -> Unit = {},
) {
    Box(modifier.fillMaxSize()) {
        WelcomeBody(state, onEvent, onGetStarted, onSettings, onDeviceLogs, onRevert)
        AutomationDialogs(state, onEvent)
        SkipStepsOverlay(
            state.skip,
            onConfirm = { onEvent(WelcomeEvent.ConfirmSkip) },
            onCancel = { onEvent(WelcomeEvent.CancelSkip) },
            onManual = { onEvent(WelcomeEvent.ManualSetup) },
        )
    }
}

@Composable
private fun WelcomeBody(
    state: WelcomeUiState,
    onEvent: (WelcomeEvent) -> Unit,
    onGetStarted: () -> Unit,
    onSettings: () -> Unit,
    onDeviceLogs: () -> Unit,
    onRevert: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wide = maxWidth >= TwoPaneMinWidth
        Column(Modifier.widthIn(max = if (wide) WelcomeWideMaxWidth else SinglePaneMaxWidth).fillMaxSize()) {
            if (wide) {
                // Unfolded: the header stays on top; hero on the left and the words with the actions on the
                // right, centered in the remaining height (scrolling only when they do not fit).
                WelcomeHeader(onSettings, Modifier.padding(start = 24.dp, end = 16.dp, top = 16.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Row(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WelcomeHero(Modifier.weight(1f).height(260.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            WelcomeText()
                            WelcomeActions(state.logsAvailable, state.canRevert, state.automation, onEvent, onGetStarted, onDeviceLogs, onRevert, Modifier.padding(top = 4.dp))
                        }
                    }
                }
            } else {
                // One scroll for everything, but the hero gives way first: it shrinks, then goes, then the
                // words and actions tighten (compact), so everything fits on a tall narrow phone; only when
                // even that does not fit does the screen scroll, as one piece.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                    NarrowWelcome(
                        height = maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding(),
                        header = { WelcomeHeader(onSettings, Modifier.padding(start = 8.dp, top = 8.dp)) },
                        hero = { WelcomeHero() },
                        text = { compact ->
                            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) { WelcomeText(compact) }
                        },
                        actions = { compact ->
                            WelcomeActions(state.logsAvailable, state.canRevert, state.automation, onEvent, onGetStarted, onDeviceLogs, onRevert, compact = compact)
                        },
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(padding),
                    )
                }
            }
        }
    }

    if (state.changesSheetVisible) {
        ChangesSheet(state.changes, onDismiss = { onEvent(WelcomeEvent.HideChanges) })
    }
}

/** The app's own header instead of a system title bar: the name set large, settings at the end. */
@Composable
private fun WelcomeHeader(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconAction(Icons.Rounded.Settings, stringResource(R.string.action_settings), onSettings)
    }
}

/** Widest the welcome screen gets when the hero sits beside the text. */
private val WelcomeWideMaxWidth = 1100.dp

/** Height range of the hero in the single-column layout; it takes what is left within it. */
private val NarrowHeroMinHeight = 96.dp
private val NarrowHeroMaxHeight = 220.dp

/**
 * The single-column welcome: header, hero, text, and the actions at the bottom. The hero gets the room
 * the rest leaves out of [height] (the visible height), up to [NarrowHeroMaxHeight]. When that is less
 * than [NarrowHeroMinHeight] the hero is left out if that lets everything fit; failing that, the text
 * and actions switch to their compact form (one recomposition, remembered for this [height]). Only
 * when even that does not fit does the layout grow taller than [height] (it scrolls), compact and
 * without the hero. Otherwise the layout is exactly [height] tall, with the actions at the bottom.
 */
@Composable
private fun NarrowWelcome(
    height: Dp,
    header: @Composable () -> Unit,
    hero: @Composable () -> Unit,
    text: @Composable (compact: Boolean) -> Unit,
    actions: @Composable (compact: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Only one form is ever composed, so the switches and links exist once (for TalkBack too).
    var compact by remember(height) { mutableStateOf(false) }
    Layout(listOf(header, hero, { text(compact) }, { actions(compact) }), modifier) { (headerM, heroM, textM, actionsM), constraints ->
        val width = constraints.maxWidth
        val gap = (if (compact) 12.dp else 20.dp).roundToPx()
        val loose = Constraints(maxWidth = width)
        val headerP = headerM.single().measure(loose)
        val textP = textM.single().measure(loose)
        val actionsP = actionsM.single().measure(loose)
        val fixed = headerP.height + textP.height + actionsP.height + 2 * gap
        val visible = height.roundToPx()
        val room = visible - fixed - gap
        val heroHeight = if (!compact && room >= NarrowHeroMinHeight.roundToPx()) minOf(room, NarrowHeroMaxHeight.roundToPx()) else 0
        if (!compact && heroHeight == 0 && room + gap < 0) compact = true
        // A shrunk hero narrows in proportion, so the panel keeps its shape instead of stretching wide.
        val heroWidth = (width.toLong() * heroHeight / NarrowHeroMaxHeight.roundToPx()).toInt().coerceAtMost(width)
        val heroP = if (heroHeight > 0) heroM.single().measure(Constraints.fixed(heroWidth, heroHeight)) else null
        val total = maxOf(visible, fixed + (heroP?.let { heroHeight + gap } ?: 0))
        layout(width, total) {
            var y = 0
            headerP.place(0, y)
            y += headerP.height + gap
            heroP?.let {
                it.place((width - heroWidth) / 2, y)
                y += heroHeight + gap
            }
            textP.place(0, y)
            actionsP.place(0, total - actionsP.height)
        }
    }
}

/** The title and the three promises; [compact] sets them smaller for a screen they would not fit. */
@Composable
private fun WelcomeText(compact: Boolean = false) {
    val title = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold)
    Text(
        stringResource(R.string.welcome_title),
        style = if (compact) title.copy(fontSize = 28.sp, lineHeight = 36.sp) else title,
        modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() },
    )
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
        Promise(Icons.Rounded.Download, R.string.welcome_promise_download,
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, compact)
        Promise(Icons.Rounded.Tune, R.string.welcome_promise_patch,
            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, compact)
        Promise(Icons.Rounded.Undo, R.string.welcome_promise_undo,
            MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, compact)
    }
}

@Composable
private fun WelcomeActions(
    logsAvailable: Boolean,
    canRevert: Boolean,
    automation: AutomationState,
    onEvent: (WelcomeEvent) -> Unit,
    onGetStarted: () -> Unit,
    onDeviceLogs: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier) {
        ActionRow {
            PrimaryAction(stringResource(R.string.welcome_get_started), onGetStarted)
            QuietAction(stringResource(R.string.welcome_what_changes), { onEvent(WelcomeEvent.ShowChanges) })
        }
        AutomationToggles(automation, onEvent, Modifier.padding(top = 4.dp))
        if (compact && logsAvailable && canRevert) {
            // Compact: both links share one row, with their short labels.
            Row(Modifier.padding(top = 4.dp)) {
                SubtleLink(stringResource(R.string.device_logs_link_short), onDeviceLogs, Modifier.weight(1f), Icons.Rounded.Terminal)
                SubtleLink(stringResource(R.string.revert_title_short), onRevert, Modifier.weight(1f), Icons.Rounded.Undo)
            }
        } else {
            val top = Modifier.padding(top = if (compact) 0.dp else 4.dp)
            if (logsAvailable) SubtleLink(stringResource(R.string.device_logs_link), onDeviceLogs, top, Icons.Rounded.Terminal)
            if (canRevert) SubtleLink(stringResource(R.string.revert_title), onRevert, top, Icons.Rounded.Undo)
        }
    }
}

@Composable
private fun Promise(icon: ImageVector, textRes: Int, container: Color, content: Color, compact: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 32.dp else 40.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (compact) 20.dp else 24.dp))
        }
        val body = MaterialTheme.typography.bodyLarge
        Text(stringResource(textRes), style = if (compact) body.copy(fontSize = 14.sp, lineHeight = 20.sp) else body)
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
                    Icon(appIcon(app.icon), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    app.purpose.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                app.grants.forEach { GrantRow(it, technical) }
            }
        }
    }
}

@Composable
private fun GrantRow(grant: Grant, technical: Boolean) {
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
            val reason = grant.why
            if (reason != null) {
                Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** The icon for a catalogue `apps` icon symbol (`Catalogue.APP_ICONS`); an unknown symbol gets the generic one. */
private fun appIcon(symbol: String): ImageVector = when (symbol) {
    "monitor-heart" -> Icons.Rounded.MonitorHeart
    "favorite" -> Icons.Rounded.Favorite
    "alarm" -> Icons.Rounded.Alarm
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
