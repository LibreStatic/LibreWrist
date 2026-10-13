package io.openshm.patcher.ui.screens.selftest

import io.openshm.patcher.ui.components.ActionRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.CheckBlob
import io.openshm.patcher.ui.components.DecorShape
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.OutlinedAction
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.SubtleLink
import io.openshm.patcher.ui.theme.PatcherTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class AllSetUiState(
    val passed: Int = 0,
    val total: Int = 0,
    /** The watch answered over ADB, so its device logs can be opened. */
    val watchReachable: Boolean = false,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AllSetScreen(
    state: AllSetUiState,
    onDone: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
    onDeviceLogs: () -> Unit = {},
    onRevert: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val status = PatcherTheme.status
    val decor = listOf(
        MaterialShapes.Sunny to colors.primaryContainer,
        MaterialShapes.Pill to colors.secondaryContainer,
        MaterialShapes.Clover4Leaf to colors.tertiaryContainer,
        MaterialShapes.Diamond to colors.primaryContainer,
        MaterialShapes.Flower to colors.secondaryContainer,
        MaterialShapes.Gem to colors.tertiaryContainer,
        MaterialShapes.Cookie7Sided to colors.primaryContainer,
        MaterialShapes.Oval to colors.secondaryContainer,
    )
    Column(modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                decor.forEachIndexed { i, (shape, color) ->
                    val angle = 2.0 * PI * i / decor.size
                    DecorShape(
                        shape, 36.dp, color,
                        Modifier.offset((120.0 * cos(angle)).dp, (120.0 * sin(angle)).dp),
                        rotation = i * 25f,
                    )
                }
                CheckBlob(180.dp, colors.tertiary, colors.onTertiary, description = stringResource(R.string.allset_title))
            }
            Text(
                stringResource(R.string.allset_title),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.allset_subtitle, state.passed, state.total),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(stringResource(R.string.device_phone), containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer)
                InfoChip(stringResource(R.string.device_watch), containerColor = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer)
                InfoChip(stringResource(R.string.allset_chip_link), containerColor = status.successContainer, contentColor = status.onSuccessContainer)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionRow {
                PrimaryAction(stringResource(R.string.action_done), onDone)
                OutlinedAction(stringResource(R.string.action_share_report), onShare, Icons.Rounded.Share)
            }
            if (state.watchReachable) SubtleLink(stringResource(R.string.device_logs_link), onDeviceLogs, icon = Icons.Rounded.Terminal)
            SubtleLink(stringResource(R.string.revert_title), onRevert, icon = Icons.Rounded.Undo)
        }
    }
}
