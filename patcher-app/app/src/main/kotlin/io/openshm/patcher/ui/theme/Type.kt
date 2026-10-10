package io.openshm.patcher.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Text styles outside the Material scale. */
@Immutable
data class PatcherTextStyles(
    /** Package ids, host:port, pairing codes (canvas: Roboto Mono 11.5-13 sp). */
    val packageId: TextStyle,
    val mono: TextStyle,
)

private fun TextStyle.sized(
    family: FontFamily,
    size: Int,
    line: Int,
    weight: Int,
    tracking: TextUnit = 0.sp,
): TextStyle = copy(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking,
)

/**
 * Roboto Flex scale taken from the canvas "Expressive language" board: the emphasized styles carry
 * the screen titles (headlineSmallEmphasized 28/34 w700), the progress percentage
 * (displayMediumEmphasized 48/52 w850) and versions (titleMediumEmphasized 20/26 w800).
 */
internal fun patcherTypography(ui: FontFamily): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.sized(ui, 57, 64, 400, (-0.25).sp),
        displayMedium = base.displayMedium.sized(ui, 45, 52, 400),
        displaySmall = base.displaySmall.sized(ui, 36, 44, 400),
        headlineLarge = base.headlineLarge.sized(ui, 32, 40, 400),
        headlineMedium = base.headlineMedium.sized(ui, 28, 36, 400),
        headlineSmall = base.headlineSmall.sized(ui, 24, 32, 400),
        titleLarge = base.titleLarge.sized(ui, 22, 28, 400),
        titleMedium = base.titleMedium.sized(ui, 16, 22, 500, 0.15.sp),
        titleSmall = base.titleSmall.sized(ui, 14, 20, 500, 0.1.sp),
        bodyLarge = base.bodyLarge.sized(ui, 16, 22, 400, 0.15.sp),
        bodyMedium = base.bodyMedium.sized(ui, 14, 20, 400, 0.25.sp),
        bodySmall = base.bodySmall.sized(ui, 13, 18, 400, 0.4.sp),
        labelLarge = base.labelLarge.sized(ui, 14, 20, 600, 0.1.sp),
        labelMedium = base.labelMedium.sized(ui, 12, 16, 600, 0.5.sp),
        labelSmall = base.labelSmall.sized(ui, 11, 16, 700, 0.5.sp),
        displayLargeEmphasized = base.displayLargeEmphasized.sized(ui, 57, 64, 800, (-0.25).sp),
        displayMediumEmphasized = base.displayMediumEmphasized.sized(ui, 48, 52, 850, (-1).sp),
        displaySmallEmphasized = base.displaySmallEmphasized.sized(ui, 36, 44, 800, (-0.25).sp),
        headlineLargeEmphasized = base.headlineLargeEmphasized.sized(ui, 32, 40, 700),
        headlineMediumEmphasized = base.headlineMediumEmphasized.sized(ui, 28, 36, 700),
        headlineSmallEmphasized = base.headlineSmallEmphasized.sized(ui, 28, 34, 700),
        titleLargeEmphasized = base.titleLargeEmphasized.sized(ui, 22, 28, 800),
        titleMediumEmphasized = base.titleMediumEmphasized.sized(ui, 20, 26, 800),
        titleSmallEmphasized = base.titleSmallEmphasized.sized(ui, 15, 20, 700, 0.1.sp),
        bodyLargeEmphasized = base.bodyLargeEmphasized.sized(ui, 16, 22, 600, 0.15.sp),
        bodyMediumEmphasized = base.bodyMediumEmphasized.sized(ui, 14, 20, 600, 0.25.sp),
        bodySmallEmphasized = base.bodySmallEmphasized.sized(ui, 13, 18, 600, 0.4.sp),
        labelLargeEmphasized = base.labelLargeEmphasized.sized(ui, 16, 22, 700, 0.15.sp),
        labelMediumEmphasized = base.labelMediumEmphasized.sized(ui, 12, 16, 700, 0.5.sp),
        labelSmallEmphasized = base.labelSmallEmphasized.sized(ui, 11, 16, 700, 0.5.sp),
    )
}

internal fun patcherTextStyles(mono: FontFamily) = PatcherTextStyles(
    packageId = TextStyle(fontFamily = mono, fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.em),
    mono = TextStyle(fontFamily = mono, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.em),
)
