package io.openshm.patcher.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

/**
 * A [Shape] drawn from a [Morph] between two Material shapes. [progress] is read while the outline
 * is created (inside the draw/layer phase), so animating it does not recompose.
 * Material shape polygons are normalized to the unit square.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class MorphShape(
    private val morph: Morph,
    private val progress: () -> Float,
) : Shape {
    private val path = Path()
    private val matrix = Matrix()

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        path.rewind()
        morph.toPath(progress().coerceIn(0f, 1f), path)
        matrix.reset()
        matrix.scale(size.width, size.height)
        val scaled = Path()
        scaled.addPath(path)
        scaled.transform(matrix)
        return Outline.Generic(scaled)
    }
}

/** A single polygon as a [Shape] (a morph that does not move). */
internal fun PolygonShape(polygon: RoundedPolygon): Shape = MorphShape(Morph(polygon, polygon)) { 0f }
