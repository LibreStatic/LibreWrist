package io.openshm.patcher.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.openshm.patcher.R
import io.openshm.patcher.ui.theme.PatcherTheme

/** Back / help row shared by the step screens. */
@Composable
fun TopActions(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconAction(
                Icons.AutoMirrored.Rounded.ArrowBack,
                stringResource(R.string.action_back),
                onBack,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun HelpAction(onHelp: () -> Unit) =
    IconAction(Icons.Rounded.Help, stringResource(R.string.action_help), onHelp)

/** Widest a single-pane screen gets; on tablets and unfolded foldables the rest is side margin. */
val SinglePaneMaxWidth = 640.dp

/** Widest a two-pane step screen gets. */
val TwoPaneMaxWidth = 1200.dp

/** From this available width on, [StepScreenScaffold] lays out its `secondary` content beside the primary one. */
val TwoPaneMinWidth = 720.dp

/** True inside [StepScreenScaffold] content while the two panes are side by side; screens use it to shrink illustrations. */
val LocalTwoPane = staticCompositionLocalOf { false }

/**
 * Centers [content] in a column capped at [SinglePaneMaxWidth]. For screens that do not use
 * [StepScreenScaffold] (the scaffold applies its own cap).
 */
@Composable
fun ContentColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = SinglePaneMaxWidth).fillMaxSize()) { content() }
    }
}

/**
 * Standard step screen: back/help row, step header, scrolling content, pinned footer. Insets are
 * handled here so content never sits under the system bars, and the content scrolls at font scale 2.
 *
 * With a [secondary] slot and at least [TwoPaneMinWidth] available, the header spans the top, the
 * primary and secondary content sit side by side (each scrolling on its own, both top-aligned) and
 * the footer spans below both; [LocalTwoPane] is true inside them. Narrower, the secondary content
 * is appended after the primary one in the same scroll. The column is capped at
 * [SinglePaneMaxWidth], or [TwoPaneMaxWidth] when two-pane, and centered.
 *
 * The footer sits on an opaque surface band, so its text and buttons never lie over the backdrop.
 */
@Composable
fun StepScreenScaffold(
    step: SetupStep,
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onHelp: (() -> Unit)? = null,
    topTrailing: @Composable () -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    secondary: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        val twoPane = secondary != null && maxWidth >= TwoPaneMinWidth
        val contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
        CompositionLocalProvider(LocalTwoPane provides twoPane) {
            Column(Modifier.widthIn(max = if (twoPane) TwoPaneMaxWidth else SinglePaneMaxWidth).fillMaxSize()) {
                TopActions(onBack = onBack, modifier = Modifier.padding(top = 8.dp)) {
                    topTrailing()
                    if (onHelp != null) HelpAction(onHelp)
                }
                StepHeader(step, title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (twoPane) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = content,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            content = secondary!!,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                        secondary?.invoke(this)
                    }
                }
                ScaffoldFooter(footer)
            }
        }
    }
}

/** Opaque band under the content, with a short fade above it so scrolled content does not end in a hard edge. */
@Composable
private fun ScaffoldFooter(footer: @Composable ColumnScope.() -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(12.dp)
                .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))),
        )
        Column(
            modifier = Modifier.fillMaxWidth().background(surface)
                .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = footer,
        )
    }
}

/** Corner radii of one row in a grouped list: 20 dp at the outer edges, 6 dp between rows. */
fun groupedShape(index: Int, count: Int, outer: Int = 20, inner: Int = 6): Shape {
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top.dp, topEnd = top.dp, bottomStart = bottom.dp, bottomEnd = bottom.dp)
}

/** One row of a grouped list (2 dp gap, asymmetric corners from the canvas). */
@Composable
fun GroupedItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    outerRadius: Int = 20,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = groupedShape(index, count, outerRadius),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/** Gap between rows of a grouped list. */
val GroupedSpacing = Arrangement.spacedBy(2.dp)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp).semantics { heading() },
    )
}

/** Small 28 dp label chip (canvas: radius 8, 12 sp semibold). */
@Composable
fun InfoChip(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    bordered: Boolean = containerColor == Color.Transparent,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        contentColor = contentColor,
        // The border follows the content color, so it stays visible on a tinted (e.g. selected) container.
        border = if (bordered) BorderStroke(1.dp, contentColor.copy(alpha = 0.5f)) else null,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 28.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Monospace package id, single line with ellipsis. */
@Composable
fun PackageId(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = PatcherTheme.text.packageId,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Icon plus explanatory text row (canvas "verified_user" note, "link" note). */
@Composable
fun NoteRow(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Centered helper line above the main action ("Waiting on 2 apps"). */
@Composable
fun FooterHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Round device badge: phone is a rounded square on primary, watch a circle on tertiary. */
@Composable
fun DeviceBadge(onWatch: Boolean, icon: ImageVector, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val container = if (onWatch) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val content = if (onWatch) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .size(size)
            .background(container, if (onWatch) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}
