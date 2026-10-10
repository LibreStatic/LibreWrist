package io.openshm.patcher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
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

/**
 * Welcome hero: phone, watch and the link between them, drawn as one fixed-size composition that is
 * centered on a rounded panel of any width (canvas Welcome artboard, restyled with expressive shapes).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeHero(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val strap = androidx.compose.ui.graphics.lerp(colors.tertiaryContainer, colors.tertiary, 0.35f)
    Box(
        modifier
            .clearAndSetSemantics { }
            .clip(RoundedCornerShape(48.dp))
            .background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 300.dp, height = 196.dp)) {
            // Soft expressive shapes behind each device.
            Box(
                Modifier.offset((-14).dp, (-6).dp).size(196.dp).rotate(12f)
                    .clip(PolygonShape(MaterialShapes.Cookie12Sided))
                    .background(colors.primaryContainer.copy(alpha = 0.6f)),
            )
            Box(
                Modifier.offset(196.dp, 42.dp).size(124.dp)
                    .clip(PolygonShape(MaterialShapes.Sunny))
                    .background(colors.tertiaryContainer.copy(alpha = 0.7f)),
            )
            // Phone: rounded body, speaker pill, a heart on screen and two content bars.
            Column(
                Modifier
                    .offset(40.dp, 6.dp)
                    .size(width = 104.dp, height = 184.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.primary),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.padding(top = 12.dp).size(width = 30.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(colors.onPrimary.copy(alpha = 0.35f)),
                )
                Box(
                    Modifier.padding(top = 26.dp).size(52.dp)
                        .clip(PolygonShape(MaterialShapes.Cookie9Sided))
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Box(
                    Modifier.padding(top = 18.dp).size(width = 64.dp, height = 9.dp)
                        .clip(RoundedCornerShape(5.dp)).background(colors.inversePrimary),
                )
                Box(
                    Modifier.padding(top = 8.dp).size(width = 42.dp, height = 9.dp)
                        .clip(RoundedCornerShape(5.dp)).background(colors.inversePrimary.copy(alpha = 0.7f)),
                )
            }
            LinkWave(colors.tertiary, Modifier.offset(150.dp, 88.dp).size(width = 52.dp, height = 20.dp), strokeWidth = 3.5.dp, cycles = 3)
            // Watch: strap behind a round face with a bezel ring and the time.
            Box(
                Modifier.offset(239.dp, 26.dp).size(width = 30.dp, height = 144.dp)
                    .clip(RoundedCornerShape(15.dp)).background(strap),
            )
            Box(
                Modifier.offset(208.dp, 52.dp).size(92.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colors.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(76.dp)
                        .border(2.dp, colors.onTertiary.copy(alpha = 0.3f), androidx.compose.foundation.shape.CircleShape),
                )
                androidx.compose.material3.Text(
                    "10:08",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = colors.onTertiary,
                )
            }
        }
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
