package io.openshm.patcher.ui.screens.build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.BuildProgressRing
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.FooterHint
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold
import io.openshm.patcher.ui.theme.PatcherTheme

/** The stages of one build, in order (they mirror the engine pipeline, not its API). */
enum class BuildStage { Unpack, Fingerprints, Apply, Rename, Rebuild, Sign, SelfCheck }

data class BuildStageRow(val stage: BuildStage, val status: CheckStatus)

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
) {
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
}

@Composable
fun BuildScreen(
    state: BuildUiState,
    onEvent: (BuildEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScreenScaffold(
        step = SetupStep.Build,
        title = stringResource(R.string.build_title),
        onBack = onBack,
        modifier = modifier,
        footer = {
            if (state.failed) {
                Text(
                    stringResource(R.string.build_error_title) + (state.error?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                PrimaryAction(stringResource(R.string.action_retry), { onEvent(BuildEvent.Retry) })
            } else {
                FooterHint(stringResource(R.string.build_can_leave))
            }
            OutlinedAction(stringResource(R.string.build_show_log), { onEvent(BuildEvent.ShowLog) }, Icons.Rounded.Article)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            BuildProgressRing(
                progress = state.progress,
                caption = stringResource(state.runningStage?.titleRes() ?: R.string.build_caption_idle),
            )
        }
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
    }
    if (state.logVisible) LogSheet(state.log) { onEvent(BuildEvent.HideLog) }
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
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.build_log_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.semantics { heading() },
            )
            if (log.isEmpty()) {
                Text(stringResource(R.string.build_log_empty), style = MaterialTheme.typography.bodyMedium)
            }
            log.forEach { Text(it, style = PatcherTheme.text.packageId) }
        }
    }
}
