package io.openshm.patcher.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Fallback palette, generated from the design canvas ("Material You" board, indigo palette, seed
 * #3D5AFE). Every role the board defines is copied verbatim; the roles the board does not define
 * (secondary, error, the extra surface containers, inverse roles) use the standard Material 3
 * tonal values for the same hue so the scheme is complete.
 *
 * Used when dynamic color is unavailable or switched off. minSdk is 31, so dynamic color is
 * available on every supported device unless the user disables it in the app.
 */

internal val FallbackLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF4355B9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    inversePrimary = Color(0xFFB9C3FF),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF8A4F67),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E5),
    onTertiaryContainer = Color(0xFF380B22),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B23),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B23),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF4355B9),
    inverseSurface = Color(0xFF2F3038),
    inverseOnSurface = Color(0xFFF2EFF9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FC),
    surfaceContainer = Color(0xFFEFEDF7),
    surfaceContainerHigh = Color(0xFFE9E7F1),
    surfaceContainerHighest = Color(0xFFE3E1EB),
)

internal val FallbackDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF293CA0),
    onPrimaryContainer = Color(0xFFDEE0FF),
    inversePrimary = Color(0xFF4355B9),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434659),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFFFB0CB),
    onTertiary = Color(0xFF541D34),
    tertiaryContainer = Color(0xFF6E3850),
    onTertiaryContainer = Color(0xFFFFD9E5),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceTint = Color(0xFFB9C3FF),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF2F3038),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
)

/**
 * Roles Material 3 does not define but the design needs for status: success (passed checks,
 * "Link" chip) and warning. They stay fixed under dynamic color so a status never changes meaning
 * with the wallpaper. Light values are the canvas tokens (`#B1F1C1`, `#00210D`, `#2E6A44`,
 * `#FFDEA6`, `#261900`); dark values are the matching tonal steps.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightStatusColors = StatusColors(
    success = Color(0xFF2E6A44),
    successContainer = Color(0xFFB1F1C1),
    onSuccessContainer = Color(0xFF00210D),
    warning = Color(0xFF7D5700),
    warningContainer = Color(0xFFFFDEA6),
    onWarningContainer = Color(0xFF261900),
)

internal val DarkStatusColors = StatusColors(
    success = Color(0xFF96D5A6),
    successContainer = Color(0xFF14522F),
    onSuccessContainer = Color(0xFFB1F1C1),
    warning = Color(0xFFF1BF60),
    warningContainer = Color(0xFF5E4200),
    onWarningContainer = Color(0xFFFFDEA6),
)
