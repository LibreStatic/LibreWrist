package io.openshm.patcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Decorative wave between the phone and the watch (the "link"). */
@Composable
fun LinkWave(color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp, cycles: Int = 3) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val amplitude = size.height / 2f - strokeWidth.toPx() / 2f
        val path = Path()
        val steps = 64
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = t * size.width
            val y = size.height / 2f - amplitude * sin(t * cycles * 2f * PI.toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * Phone, link and watch, as in the canvas Home / Disable / Welcome artboards. [scale] 1.0 is the
 * Home size (phone 40x66, watch cookie 60).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PhoneWatchIllustration(modifier: Modifier = Modifier, scale: Float = 1f, linkColor: Color = MaterialTheme.colorScheme.primary) {
    Row(modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 40.dp * scale, height = 66.dp * scale)
                .clip(RoundedCornerShape(12.dp * scale))
                .background(MaterialTheme.colorScheme.primary),
        )
        LinkWave(linkColor, Modifier.size(width = 64.dp * scale, height = 16.dp * scale), strokeWidth = 4.dp * scale)
        Box(
            Modifier
                .size(60.dp * scale)
                .clip(PolygonShape(MaterialShapes.Clover4Leaf))
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

/** Welcome hero: phone, watch and the link on a large rounded panel (canvas Welcome artboard). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeHero(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val accentBar = androidx.compose.ui.graphics.lerp(colors.tertiaryContainer, colors.tertiary, 0.35f)
    Box(
        modifier
            .clearAndSetSemantics { }
            .clip(RoundedCornerShape(48.dp))
            .background(colors.surfaceContainerHigh),
    ) {
        Box(
            Modifier
                .offset((-40).dp, (-50).dp)
                .size(220.dp)
                .clip(PolygonShape(MaterialShapes.Cookie12Sided))
                .background(colors.primaryContainer.copy(alpha = 0.55f)),
        )
        Column(
            Modifier
                .offset(62.dp, 58.dp)
                .size(width = 118.dp, height = 204.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.primary),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(width = 62.dp, height = 10.dp).clip(RoundedCornerShape(5.dp)).background(colors.primaryContainer))
            Box(Modifier.size(width = 62.dp, height = 10.dp).clip(RoundedCornerShape(5.dp)).background(colors.inversePrimary))
            Box(Modifier.size(width = 40.dp, height = 10.dp).clip(RoundedCornerShape(5.dp)).background(colors.inversePrimary))
        }
        LinkWave(colors.tertiary, Modifier.offset(172.dp, 140.dp).size(width = 64.dp, height = 20.dp), strokeWidth = 3.5.dp, cycles = 4)
        Box(
            Modifier
                .offset(262.dp, 76.dp)
                .size(width = 22.dp, height = 168.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(accentBar),
        )
        Box(
            Modifier
                .offset(226.dp, 104.dp)
                .size(94.dp)
                .clip(PolygonShape(MaterialShapes.Clover4Leaf))
                .background(colors.tertiary),
        )
        androidx.compose.material3.Text(
            "10:08",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onTertiary,
            modifier = Modifier.offset(252.dp, 140.dp).width(42.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Big status blob with a check on top (Build complete / All set). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CheckBlob(size: Dp, container: Color, content: Color, modifier: Modifier = Modifier, description: String) {
    Box(
        modifier
            .size(size)
            .clip(PolygonShape(MaterialShapes.Cookie12Sided))
            .background(container)
            .semantics { contentDescription = description; role = Role.Image },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Rounded.Check,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(size * 0.4f),
        )
    }
}

/** Small decorative shape used to scatter around hero blobs. */
@Composable
fun DecorShape(polygon: androidx.graphics.shapes.RoundedPolygon, size: Dp, color: Color, modifier: Modifier = Modifier, rotation: Float = 0f) {
    Box(modifier.size(size).rotate(rotation).clip(PolygonShape(polygon)).background(color).clearAndSetSemantics { })
}
