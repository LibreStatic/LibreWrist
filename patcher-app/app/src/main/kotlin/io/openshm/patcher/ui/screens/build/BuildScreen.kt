package io.openshm.patcher.ui.screens.build

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.components.BuildProgressRing
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PolygonShape
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme
import kotlin.random.Random
import kotlinx.coroutines.delay

/** The stages of one build, in order (they mirror the engine pipeline, not its API). */
enum class BuildStage { Unpack, Fingerprints, Apply, Rename, Rebuild, Sign, SelfCheck }

data class BuildStageRow(val stage: BuildStage, val status: CheckStatus)

/** The output being built right now: what the "current app" card shows. [number] is 1-based. */
data class CurrentApp(
    val outputId: String,
    val label: String,
    val packageName: String,
    val device: Device,
    val number: Int,
    val total: Int,
)

data class BuildUiState(
    val stages: List<BuildStageRow> = BuildStage.entries.map { BuildStageRow(it, CheckStatus.Pending) },
    val patchCount: Int = 0,
    val log: List<String> = emptyList(),
    val logVisible: Boolean = false,
    val failed: Boolean = false,
    /** Why the build failed (shown when [failed]). */
    val error: String? = null,
    /** Overall progress across all outputs; when null it is derived from the stage list. */
    val overall: Float? = null,
    val running: Boolean = false,
    /** The user stopped the build; nothing was kept. */
    val cancelled: Boolean = false,
    /** A cancel was asked for and the build is still winding down. */
    val cancelRequested: Boolean = false,
    val currentApp: CurrentApp? = null,
) {
    /** True while one of the last two apps is being built: time for the reassuring messages. */
    val almostDone: Boolean get() = running && currentApp != null && currentApp.total - (currentApp.number - 1) <= 2

    val progress: Float
        get() {
            overall?.let { return it.coerceIn(0f, 1f) }
            val done = stages.count { it.status == CheckStatus.Passed }
            val running = if (stages.any { it.status == CheckStatus.Running }) 0.5f else 0f
            return ((done + running) / stages.size).coerceIn(0f, 1f)
        }
    val runningStage: BuildStage? get() = stages.firstOrNull { it.status == CheckStatus.Running }?.stage
}

sealed interface BuildEvent {
    data object ShowLog : BuildEvent
    data object HideLog : BuildEvent
    data object Retry : BuildEvent
    data object Cancel : BuildEvent

    /** The user leaves a cancelled build behind. */
    data object Dismiss : BuildEvent
}

/** A random index in [0, size) that differs from [current] (when there is more than one). */
internal fun nextAlmostDoneIndex(current: Int, size: Int, random: Random): Int {
    if (size <= 1) return 0
    val next = random.nextInt(size - 1)
    return if (next >= current) next + 1 else next
}

/** How many of the last log lines the live tail shows. */
internal const val LOG_TAIL_LINES = 4

@Composable
fun BuildScreen(
    state: BuildUiState,
    onEvent: (BuildEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    StepScreenScaffold(
        step = SetupStep.Build,
        title = stringResource(R.string.build_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            when {
                state.failed -> {
                    Text(
                        stringResource(R.string.build_error_title) + (state.error?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    PrimaryAction(stringResource(R.string.action_retry), { onEvent(BuildEvent.Retry) })
                }
                state.cancelled -> {
                    FooterHint(stringResource(R.string.build_cancelled))
                    PrimaryAction(stringResource(R.string.build_restart), { onEvent(BuildEvent.Retry) })
                    OutlinedAction(stringResource(R.string.action_back), onBack, Icons.AutoMirrored.Rounded.ArrowBack)
                }
                else -> FooterHint(stringResource(R.string.build_can_leave))
            }
            OutlinedAction(stringResource(R.string.build_show_log), { onEvent(BuildEvent.ShowLog) }, Icons.Rounded.Article)
            if (state.running) {
                TextButton(
                    onClick = { confirmCancel = true },
                    enabled = !state.cancelRequested,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            BuildProgressRing(
                progress = state.progress,
                caption = stringResource(state.runningStage?.titleRes() ?: R.string.build_caption_idle),
            )
        }
        if (state.almostDone) AlmostDoneMessage()
        state.currentApp?.let { CurrentAppCard(it) }
        Column(verticalArrangement = GroupedSpacing) {
            state.stages.forEachIndexed { index, row ->
                val running = row.status == CheckStatus.Running
                GroupedItem(
                    index, state.stages.size,
                    containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = if (running) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(
                        Modifier.heightIn(min = if (running) 56.dp else 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusShape(row.status)
                        Text(
                            if (row.stage == BuildStage.Apply) {
                                pluralStringResource(R.plurals.build_stage_apply, state.patchCount, state.patchCount)
                            } else {
                                stringResource(row.stage.titleRes())
                            },
                            style = if (running) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        LogTail(state.log)
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.build_cancel_title)) },
            text = { Text(stringResource(R.string.build_cancel_body)) },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; onEvent(BuildEvent.Cancel) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.build_cancel_keep)) }
            },
        )
    }
    if (state.logVisible) LogSheet(state.log) { onEvent(BuildEvent.HideLog) }
}

/**
 * A short reassuring line for the last two apps, picked at random (never the same twice in a row)
 * and swapped every ~8 s with a crossfade. With reduced motion it swaps without a fade and half as
 * often. Deliberately not a live region: TalkBack would read it out over and over.
 */
@Composable
private fun AlmostDoneMessage() {
    val messages = stringArrayResource(R.array.build_almost_done)
    val reduced = LocalReducedMotion.current
    var index by rememberSaveable { mutableIntStateOf(Random.nextInt(messages.size)) }
    LaunchedEffect(messages.size, reduced) {
        while (true) {
            delay(if (reduced) 16_000L else 8_000L)
            index = nextAlmostDoneIndex(index, messages.size, Random)
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Crossfade(
            targetState = index.coerceIn(0, messages.lastIndex),
            animationSpec = if (reduced) snap() else tween(700),
            label = "almost-done",
        ) { i ->
            Text(
                messages[i],
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Icon, name, package, device and position of the output being built; animates when the app changes. */
@Composable
private fun CurrentAppCard(app: CurrentApp) {
    val reduced = LocalReducedMotion.current
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = app,
            contentKey = { it.outputId },
            transitionSpec = {
                if (reduced) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "current-app",
        ) { shown ->
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(shown)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shown.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        shown.packageName,
                        style = PatcherTheme.text.packageId,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        InfoChip(
                            stringResource(if (shown.device == Device.Phone) R.string.device_phone else R.string.device_watch),
                            icon = deviceGlyph(shown.device),
                        )
                        Text(
                            stringResource(R.string.build_app_position, shown.number, shown.total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun deviceGlyph(device: Device) = if (device == Device.Phone) Icons.Rounded.PhoneAndroid else Icons.Rounded.Watch

/** The real icon when the loader has one, else the device glyph inside a Material shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppIcon(app: CurrentApp) {
    val loader = LocalAppIconLoader.current
    val bitmap by produceState<ImageBitmap?>(null, app.outputId) { value = loader.load(app.outputId) }
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        val icon = bitmap
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(PolygonShape(MaterialShapes.Cookie9Sided))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    deviceGlyph(app.device), contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** The last few log lines, live, in a quiet card. The full log is in the sheet. */
@Composable
private fun LogTail(log: List<String>) {
    if (log.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            log.takeLast(LOG_TAIL_LINES).forEach {
                Text(
                    it, style = PatcherTheme.text.packageId, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun BuildStage.titleRes(): Int = when (this) {
    BuildStage.Unpack -> R.string.build_stage_unpack
    BuildStage.Fingerprints -> R.string.build_stage_fingerprints
    BuildStage.Apply -> R.string.build_stage_apply_short
    BuildStage.Rename -> R.string.build_stage_rename
    BuildStage.Rebuild -> R.string.build_stage_rebuild
    BuildStage.Sign -> R.string.build_stage_sign
    BuildStage.SelfCheck -> R.string.build_stage_selfcheck
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(log: List<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val listState = rememberLazyListState()
        // Follow the newest line: item 0 is the title, so the last log line is at index log.size.
        LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size) }
        LazyColumn(
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.build_log_title),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (log.isEmpty()) item { Text(stringResource(R.string.build_log_empty), style = MaterialTheme.typography.bodyMedium) }
            items(log) { Text(it, style = PatcherTheme.text.packageId) }
        }
    }
}
