package io.openshm.patcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

internal val LocalStatusColors = compositionLocalOf { LightStatusColors }
internal val LocalPatcherTextStyles = compositionLocalOf<PatcherTextStyles> {
    error("PatcherTheme is not installed")
}

/** Accessors for the theme extensions that Material 3 does not provide. */
object PatcherTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current

    val text: PatcherTextStyles
        @Composable @ReadOnlyComposable get() = LocalPatcherTextStyles.current
}

/**
 * Material 3 Expressive theme. Dynamic color (API 31+, always true for minSdk 31) is on by default;
 * [dynamicColor] = false uses the fallback indigo palette from the design canvas.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PatcherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> FallbackDarkColors
        else -> FallbackLightColors
    }
    val typography = remember(context) { patcherTypography(PatcherFonts.uiFamily(context)) }
    val textStyles = remember(context) { patcherTextStyles(PatcherFonts.monoFamily(context)) }
    val reducedMotion = rememberReducedMotion()

    CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors,
        LocalPatcherTextStyles provides textStyles,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = typography,
            content = content,
        )
    }
}
