package io.openshm.patcher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.openshm.patcher.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** Which accent a screen's main action uses: phone steps are primary, watch steps tertiary. */
enum class Accent { Primary, Tertiary }

@Composable
private fun Accent.container(): Color =
    if (this == Accent.Primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

@Composable
private fun Accent.content(): Color =
    if (this == Accent.Primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary

/** 48 dp icon-only button. [description] is the TalkBack label (matches the canvas aria-label). */
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = description)
    }
}

/** Full-width 56 dp pill button used as the main action of a screen (canvas "Get started", "Build"). */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Accent = Accent.Primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.container(),
            contentColor = accent.content(),
            // Opaque: the Material defaults are translucent and let the backdrop motifs show through.
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLargeEmphasized)
    }
}

/** Quiet 48 dp text action under the main one ("What will change on my devices?", "Skip for now"). */
@Composable
fun QuietAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Accent = Accent.Primary,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = accent.container()),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Text action on a tinted container (primary, secondary or tertiary container): [onColor] is that
 * container's on-colour, so the label keeps its contrast where the default primary would not.
 */
@Composable
fun ContainerTextAction(
    text: String,
    onClick: () -> Unit,
    onColor: Color,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = onColor),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Outlined 48 dp action ("Show log"). */
@Composable
fun OutlinedAction(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Split button with the canvas' asymmetric corners (24 dp outside, 6 dp at the split, 2 dp gap):
 * the leading part runs the main action, the trailing chevron expands "more ways".
 */
@Composable
fun SplitAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    moreDescription: String,
    expanded: Boolean,
    onToggleMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ButtonDefaults.buttonColors()
    val stateText = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 6.dp, bottomEnd = 6.dp),
            colors = colors,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
        Button(
            onClick = onToggleMore,
            modifier = Modifier
                .width(52.dp)
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = moreDescription
                    stateDescription = stateText
                },
            shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp),
            colors = colors,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

/**
 * Two or more actions in one row when their labels fit on one line there, else stacked full width in
 * the given order (the first, the main action, on top). In a row the columns are equal when every
 * label fits in an equal share, otherwise each gets its label's width plus an equal part of the rest.
 * The order is reversed in a row so the main action sits at the end, where Material dialogs put the
 * confirming one.
 */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val width = constraints.maxWidth
        val n = measurables.size
        val free = width - gap * (n - 1).coerceAtLeast(0)
        val natural = if (constraints.hasBoundedWidth) measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) } else emptyList()
        val widths: List<Int>? = when {
            n < 2 || natural.isEmpty() -> null
            natural.all { it <= free / n } -> List(n) { free / n }
            natural.sum() <= free -> {
                val extra = (free - natural.sum()) / n
                natural.map { it + extra }
            }
            else -> null
        }
        if (widths != null) {
            val height = measurables.indices.maxOf { measurables[it].minIntrinsicHeight(widths[it]) }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(androidx.compose.ui.unit.Constraints.fixed(widths[i], height).let {
                    androidx.compose.ui.unit.Constraints(minWidth = it.minWidth, maxWidth = it.maxWidth, minHeight = height)
                })
            }
            val rowHeight = placeables.maxOf { it.height }
            layout(width, rowHeight) {
                var x = 0
                placeables.reversed().forEach { p ->
                    p.placeRelative(x, (rowHeight - p.height) / 2)
                    x += p.width + gap
                }
            }
        } else {
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = width, minHeight = 0)) }
            val total = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(width, total) {
                var y = 0
                placeables.forEach { p ->
                    p.placeRelative(0, y)
                    y += p.height + gap
                }
            }
        }
    }
}
