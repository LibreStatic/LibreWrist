package io.openshm.patcher.ui.screens.build

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.data.Device
import io.openshm.patcher.ui.components.CheckBlob
import io.openshm.patcher.ui.components.DecorShape
import io.openshm.patcher.ui.components.GroupedItem
import io.openshm.patcher.ui.components.GroupedSpacing
import io.openshm.patcher.ui.components.InfoChip
import io.openshm.patcher.ui.components.NoteRow
import io.openshm.patcher.ui.components.PackageId
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.TopActions

/** One produced app: its label, the final package name (prefix already applied) and size. */
data class BuiltOutput(val id: String, val label: String, val device: Device, val packageName: String, val sizeBytes: Long)

data class BuildDoneUiState(
    val outputs: List<BuiltOutput> = emptyList(),
    val patchCount: Int = 0,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildDoneScreen(
    state: BuildDoneUiState,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxSize().background(colors.surface).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopActions(onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                DecorShape(MaterialShapes.Sunny, 36.dp, colors.secondaryContainer, Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 12.dp))
                DecorShape(MaterialShapes.Pill, 28.dp, colors.primaryContainer, Modifier.align(Alignment.BottomEnd).padding(end = 44.dp, bottom = 8.dp), rotation = 30f)
                DecorShape(MaterialShapes.Diamond, 24.dp, colors.tertiaryContainer, Modifier.align(Alignment.TopEnd).padding(end = 52.dp, top = 24.dp))
                CheckBlob(200.dp, colors.primary, colors.onPrimary, description = stringResource(R.string.build_done_title))
            }
            Text(
                stringResource(R.string.build_done_title),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight(850)),
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                pluralStringResource(R.plurals.build_done_subtitle_apps, state.outputs.size, state.outputs.size) +
                    " · " + pluralStringResource(R.plurals.build_done_subtitle_patches, state.patchCount, state.patchCount) +
                    " · " + stringResource(R.string.build_done_ready),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = GroupedSpacing) {
                state.outputs.forEachIndexed { index, out ->
                    GroupedItem(index, state.outputs.size, outerRadius = 32) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(out.label, style = MaterialTheme.typography.titleSmall)
                                PackageId(out.packageName)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                InfoChip(stringResource(if (out.device == Device.Phone) R.string.device_phone else R.string.device_watch))
                                if (out.sizeBytes > 0) {
                                    Text(
                                        stringResource(R.string.size_mb, out.sizeBytes / (1024.0 * 1024.0)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            NoteRow(Icons.Rounded.Link, stringResource(R.string.build_done_link_note))
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            PrimaryAction(stringResource(R.string.build_done_install), onInstall)
        }
    }
}
