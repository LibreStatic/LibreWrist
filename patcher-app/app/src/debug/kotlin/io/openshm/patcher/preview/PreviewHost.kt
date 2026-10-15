package io.openshm.patcher.preview

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.ui.theme.LocalReducedMotion
import io.openshm.patcher.ui.theme.PatcherTheme

/** The two palettes every screen is checked with: wallpaper-derived and the canvas fallback. */
enum class ColorMode { Dynamic, Fallback }

class ColorModeProvider : PreviewParameterProvider<ColorMode> {
    override val values: Sequence<ColorMode> = ColorMode.entries.asSequence()
}

/**
 * Multipreview: light and dark at font scale 1.0 and 2.0. Combine with
 * `@PreviewParameter(ColorModeProvider::class)` for the dynamic / fallback axis.
 */
@Preview(name = "Light 1.0", group = "screen", uiMode = Configuration.UI_MODE_NIGHT_NO, fontScale = 1f, device = PHONE)
@Preview(name = "Dark 1.0", group = "screen", uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 1f, device = PHONE)
@Preview(name = "Light 2.0", group = "screen", uiMode = Configuration.UI_MODE_NIGHT_NO, fontScale = 2f, device = PHONE)
@Preview(name = "Dark 2.0", group = "screen", uiMode = Configuration.UI_MODE_NIGHT_YES, fontScale = 2f, device = PHONE)
annotation class ScreenPreviews

internal const val PHONE = "spec:width=412dp,height=915dp,dpi=420"

@Composable
internal fun PreviewHost(mode: ColorMode, content: @Composable () -> Unit) {
    // IDE previews have no PatcherApplication; under Robolectric / the device it is already installed.
    AppCatalogue.ensure(LocalContext.current)
    AppCatalogue.followLocales(LocalContext.current)
    // Same root as PatcherApp: a Surface, so text without an explicit color gets the theme's content color.
    PatcherTheme(dynamicColor = mode == ColorMode.Dynamic) {
        // Previews and drivers stay still: endless animations would never let a test clock go idle.
        CompositionLocalProvider(LocalReducedMotion provides true) {
            Surface(color = MaterialTheme.colorScheme.surface, content = content)
        }
    }
}
