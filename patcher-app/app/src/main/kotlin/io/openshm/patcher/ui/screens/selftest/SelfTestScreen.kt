package io.openshm.patcher.ui.screens.selftest

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.IconAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SectionTitle
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.components.StepScreenScaffold

data class SelfTestRow(
    val id: String,
    @StringRes val title: Int,
    val status: CheckStatus,
    /** Shown on a failed row when the app knows a way to repair it. */
    val fixable: Boolean = false,
)

data class SelfTestSection(@StringRes val title: Int, val rows: List<SelfTestRow>)

/** A check only the person holding the devices can answer ("Did the watch buzz?"). */
data class HumanCheck(val index: Int, val total: Int, @StringRes val question: Int)

data class SelfTestUiState(
    val sections: List<SelfTestSection> = emptyList(),
    val humanCheck: HumanCheck? = null,
) {
    private val allRows get() = sections.flatMap { it.rows }
    val passedCount: Int get() = allRows.count { it.status == CheckStatus.Passed }
    val totalCount: Int get() = allRows.size
    val finished: Boolean get() = humanCheck == null && allRows.none { it.status == CheckStatus.Pending || it.status == CheckStatus.Running }
    val allPassed: Boolean get() = finished && allRows.all { it.status == CheckStatus.Passed }
}

sealed interface SelfTestEvent {
    data class Fix(val rowId: String) : SelfTestEvent
    data object Share : SelfTestEvent
    data class Answer(val yes: Boolean) : SelfTestEvent
}

@Composable
fun SelfTestScreen(
    state: SelfTestUiState,
    onEvent: (SelfTestEvent) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScreenScaffold(
        step = SetupStep.SelfTest,
        title = stringResource(R.string.selftest_title),
        onBack = onBack,
        modifier = modifier,
        topTrailing = {
            IconAction(Icons.Rounded.Share, stringResource(R.string.action_share_report), { onEvent(SelfTestEvent.Share) })
        },
        footer = { PrimaryAction(stringResource(R.string.action_continue), onContinue, enabled = state.allPassed) },
    ) {
        state.sections.forEach { section ->
            Column {
                SectionTitle(stringResource(section.title))
                Column(verticalArrangement = GroupedSpacing) {
                    section.rows.forEachIndexed { index, row -> CheckRow(index, section.rows.size, row, onEvent) }
                }
            }
        }
        state.humanCheck?.let { HumanCheckCard(it, onEvent) }
    }
}

@Composable
private fun CheckRow(index: Int, count: Int, row: SelfTestRow, onEvent: (SelfTestEvent) -> Unit) {
    val failed = row.status == CheckStatus.Failed
    GroupedItem(
        index, count,
        containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusShape(row.status)
            Text(stringResource(row.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (failed && row.fixable) {
                TextButton(onClick = { onEvent(SelfTestEvent.Fix(row.id)) }) { Text(stringResource(R.string.action_fix)) }
            }
        }
    }
}

@Composable
private fun HumanCheckCard(check: HumanCheck, onEvent: (SelfTestEvent) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.selftest_human_label, check.index, check.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inversePrimary,
            )
            Text(stringResource(check.question), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Button(
                    onClick = { onEvent(SelfTestEvent.Answer(true)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.inversePrimary,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) { Text(stringResource(R.string.action_yes)) }
                OutlinedButton(
                    onClick = { onEvent(SelfTestEvent.Answer(false)) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                ) { Text(stringResource(R.string.action_no)) }
            }
        }
    }
}
