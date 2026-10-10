package io.openshm.patcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.theme.LocalReducedMotion

/** Wavy linear progress (Material 3 Expressive) for downloads. The wave stops with reduced motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavyDownloadProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.primaryContainer,
    description: String = stringResource(R.string.progress_download_description, (progress * 100).toInt()),
) {
    val reduced = LocalReducedMotion.current
    LinearWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().semantics { contentDescription = description },
        color = color,
        trackColor = trackColor,
        waveSpeed = if (reduced) 0.dp else 20.dp,
    )
}

/**
 * Large circular wavy ring with the percentage and a caption in the middle (canvas Build artboard,
 * 200 dp, `displayMediumEmphasized` number). Announced as one progress bar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildProgressRing(
    progress: Float,
    caption: String,
    modifier: Modifier = Modifier,
    ringSize: Dp = 200.dp,
) {
    val reduced = LocalReducedMotion.current
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val description = stringResource(R.string.progress_build_description, percent)
    val density = LocalDensity.current
    val stroke = with(density) { Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round) }
    Box(
        modifier = modifier.size(ringSize).semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(ringSize).clearAndSetSemantics { },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primaryContainer,
            stroke = stroke,
            trackStroke = stroke,
            waveSpeed = if (reduced) 0.dp else 20.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$percent%", style = MaterialTheme.typography.displayMediumEmphasized)
            Text(
                caption,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
