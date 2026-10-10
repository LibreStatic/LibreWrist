package io.openshm.patcher.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme

enum class CheckStatus { Pending, Running, Passed, Warning, Failed }

@Composable
fun CheckStatus.description(): String = stringResource(
    when (this) {
        CheckStatus.Pending -> R.string.status_pending
        CheckStatus.Running -> R.string.status_running
        CheckStatus.Passed -> R.string.status_passed
        CheckStatus.Warning -> R.string.status_warning
        CheckStatus.Failed -> R.string.status_failed
    },
)

/**
 * Status as shape, color and icon (canvas "Status is shape, color and icon"): a pending ring, a
 * spinning cookie while running, a green cookie with a check when passed, a triangle for a warning
 * and a red burst with a cross when failed. The status is announced through [description].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatusShape(
    status: CheckStatus,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    description: String = status.description(),
) {
    val colors = MaterialTheme.colorScheme
    val statusColors = PatcherTheme.status
    val semanticsModifier = modifier
        .size(size)
        .semantics { contentDescription = description; role = Role.Image }
    when (status) {
        CheckStatus.Pending -> Box(
            semanticsModifier
                .padding(size / 13)
                .border(2.dp, colors.outline, CircleShape),
        )
        CheckStatus.Running -> {
            val reduced = LocalReducedMotion.current
            val rotation = if (reduced) {
                0f
            } else {
                val transition = rememberInfiniteTransition(label = "running")
                transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
                    label = "rotation",
                ).value
            }
            Box(
                semanticsModifier
                    .rotate(rotation)
                    .clip(PolygonShape(MaterialShapes.Cookie9Sided))
                    .background(colors.primary),
            )
        }
        CheckStatus.Passed -> StatusGlyph(
            semanticsModifier, PolygonShape(MaterialShapes.Cookie12Sided),
            statusColors.successContainer, statusColors.onSuccessContainer, Icons.Rounded.Check,
        )
        CheckStatus.Warning -> StatusGlyph(
            semanticsModifier, PolygonShape(MaterialShapes.Triangle),
            statusColors.warningContainer, statusColors.onWarningContainer, Icons.Rounded.PriorityHigh,
        )
        CheckStatus.Failed -> StatusGlyph(
            semanticsModifier, PolygonShape(MaterialShapes.Burst),
            colors.error, colors.onError, Icons.Rounded.Close,
        )
    }
}

@Composable
private fun StatusGlyph(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Box(modifier.clip(shape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
    }
}
