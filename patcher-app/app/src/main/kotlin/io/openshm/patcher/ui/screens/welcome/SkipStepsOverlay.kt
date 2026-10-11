package io.openshm.patcher.ui.screens.welcome

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.components.CheckStatus
import io.openshm.patcher.ui.components.PolygonShape
import io.openshm.patcher.ui.components.PrimaryAction
import io.openshm.patcher.ui.components.QuietAction
import io.openshm.patcher.ui.components.SetupStep
import io.openshm.patcher.ui.components.StatusShape
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.SkippedStep
import io.openshm.patcher.ui.screens.profile.titleRes
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme

/**
 * Dims the welcome screen while the remembered watch is asked, then shows a large check, the steps
 * passed over and why, until the user confirms. Back cancels and skips nothing.
 * Drawn in the screen's own window (a scrim and a centered card), not as a platform dialog.
 */
@Composable
internal fun SkipStepsOverlay(dialog: SkipDialog?, onConfirm: () -> Unit, onCancel: () -> Unit, onManual: () -> Unit) {
    // Keeps the last content while the overlay fades out.
    var last by remember { mutableStateOf(dialog) }
    if (dialog != null) last = dialog
    BackHandler(enabled = dialog != null, onBack = onCancel)
    val pane = stringResource(R.string.skip_title)
    // The app centers a width-capped column; the scrim reaches past it to cover the whole window.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    AnimatedVisibility(dialog != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxHeight()
                .requiredWidth(windowWidth)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                // Swallows taps so the screen underneath stays still.
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .semantics { paneTitle = pane }
                    .animateEnterScale(),
            ) {
                AnimatedContent(
                    last,
                    contentKey = { it is SkipDialog.Skipping },
                    transitionSpec = { fadeIn() togetherWith fadeOut() using SizeTransform() },
                    label = "skipDialog",
                ) { shown ->
                    if (shown is SkipDialog.Skipping) SkippingContent(shown.steps, onConfirm, onManual) else CheckingContent()
                }
            }
        }
    }
}

/** The card grows in from slightly smaller, like a dialog. */
@Composable
private fun Modifier.animateEnterScale(): Modifier {
    val reduced = LocalReducedMotion.current
    var entered by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(
        if (entered) 1f else 0.9f,
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "cardScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckingContent() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoadingIndicator(Modifier.size(80.dp))
        Text(stringResource(R.string.skip_checking), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkippingContent(steps: List<SkippedStep>, onConfirm: () -> Unit, onManual: () -> Unit) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(
        if (shown) 1f else 0.4f,
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "checkScale",
    )
    val rotation by animateFloatAsState(
        if (shown) 0f else -90f,
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessVeryLow),
        label = "checkRotation",
    )
    val status = PatcherTheme.status
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(112.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = rotation }
                .clip(PolygonShape(MaterialShapes.Cookie12Sided))
                .background(status.successContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = status.onSuccessContainer, modifier = Modifier.size(56.dp))
        }
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.skip_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.skip_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEach { SkippedCard(it) }
        }
        PrimaryAction(stringResource(R.string.skip_confirm), onConfirm, modifier = Modifier.padding(top = 8.dp))
        QuietAction(stringResource(R.string.skip_manual), onManual)
    }
}

@Composable
private fun SkippedCard(step: SkippedStep) {
    val (setupStep, titleRes) = step.route.stepInfo() ?: return
    val reason = step.watch?.let { stringResource(step.reason, it.versionLabel, stringResource(it.profile.titleRes())) }
        ?: stringResource(step.reason)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusShape(CheckStatus.Passed, size = 28.dp)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.skip_card_step, setupStep.number, stringResource(titleRes)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(reason, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun Route.stepInfo(): Pair<SetupStep, Int>? = when (this) {
    Route.Profile -> SetupStep.Profile to R.string.profile_title
    Route.WearablePairing -> SetupStep.WearablePairing to R.string.wearable_title
    Route.WatchPair -> SetupStep.WatchPair to R.string.watchpair_title
    else -> null
}
