package io.openshm.patcher.ui.screens.acquire

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.IconAction

/**
 * The guided browser around the APKMirror page. The page itself is a WebView ([ApkMirrorWebView])
 * supplied through [page]; the default renders a labeled placeholder so the chrome can be previewed
 * and rendered headlessly.
 */
data class GuidedBrowserUiState(
    val host: String,
    val appLabel: String,
    val coachStep: Int,
    val coachTotal: Int,
    val coachTitle: String,
    val coachBody: String,
    /** The exact build to pick, always visible, e.g. "1.4.6.001 · APK, not bundle · nodpi · arm64-v8a + armeabi-v7a". */
    val target: String = "",
    val notice: BrowserNotice? = null,
)

/** A short message over the page, such as a blocked link; [canOpenExternally] offers the system browser. */
data class BrowserNotice(val text: String, val canOpenExternally: Boolean = false)

sealed interface GuidedBrowserEvent {
    data object Close : GuidedBrowserEvent
    data object Reload : GuidedBrowserEvent
    data object OpenNoticeExternally : GuidedBrowserEvent
    data object DismissNotice : GuidedBrowserEvent
    data object UseFile : GuidedBrowserEvent
    data object OpenInBrowser : GuidedBrowserEvent
    data object BackToRightPage : GuidedBrowserEvent
}

@Composable
fun GuidedBrowserScreen(
    state: GuidedBrowserUiState,
    onEvent: (GuidedBrowserEvent) -> Unit,
    modifier: Modifier = Modifier,
    page: @Composable () -> Unit = { WebPagePlaceholder() },
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(
                Icons.Rounded.Close, stringResource(R.string.action_close),
                { onEvent(GuidedBrowserEvent.Close) }, tint = MaterialTheme.colorScheme.onSurface,
            )
            Column(Modifier.weight(1f)) {
                Text(state.appLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.host, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconAction(Icons.Rounded.MoreVert, stringResource(R.string.action_more_options), { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_reload)) },
                        onClick = { menuOpen = false; onEvent(GuidedBrowserEvent.Reload) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_open_external)) },
                        onClick = { menuOpen = false; onEvent(GuidedBrowserEvent.OpenInBrowser) },
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            page()
            Column(
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                state.notice?.let { NoticeCard(it, onEvent) }
                CoachCard(state)
                NavPill(state, onEvent)
            }
        }
    }
}

@Composable
private fun WebPagePlaceholder() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.TopCenter) {
        Text(
            stringResource(R.string.browser_page_placeholder),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 32.dp),
        )
    }
}

@Composable
private fun NoticeCard(notice: BrowserNotice, onEvent: (GuidedBrowserEvent) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(notice.text, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (notice.canOpenExternally) {
                    TextButton({ onEvent(GuidedBrowserEvent.OpenNoticeExternally) }) {
                        Text(stringResource(R.string.browser_notice_open))
                    }
                }
                TextButton({ onEvent(GuidedBrowserEvent.DismissNotice) }) { Text(stringResource(R.string.browser_notice_dismiss)) }
            }
        }
    }
}

@Composable
private fun CoachCard(state: GuidedBrowserUiState) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.browser_coach_progress, state.coachStep, state.coachTotal, state.coachTitle),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.inversePrimary,
            )
            Text(state.coachBody, style = MaterialTheme.typography.bodyMedium)
            if (state.target.isNotEmpty()) {
                Text(
                    stringResource(R.string.browser_target_line, state.target),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun NavPill(state: GuidedBrowserUiState, onEvent: (GuidedBrowserEvent) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StepDots(state.coachStep, state.coachTotal, Modifier.padding(horizontal = 12.dp))
            IconAction(Icons.Rounded.FolderOpen, stringResource(R.string.browser_use_file), { onEvent(GuidedBrowserEvent.UseFile) })
            IconAction(Icons.Rounded.OpenInBrowser, stringResource(R.string.browser_open_external), { onEvent(GuidedBrowserEvent.OpenInBrowser) })
            IconAction(Icons.Rounded.UTurnLeft, stringResource(R.string.browser_back_to_page), { onEvent(GuidedBrowserEvent.BackToRightPage) })
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.browser_step_dots, current, total)
    Row(modifier.semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(if (index + 1 == current) 10.dp else 8.dp)
                    .background(
                        if (index + 1 <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}
