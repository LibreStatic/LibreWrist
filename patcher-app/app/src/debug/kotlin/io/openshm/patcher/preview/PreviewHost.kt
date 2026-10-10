package io.openshm.patcher.preview

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
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
    PatcherTheme(dynamicColor = mode == ColorMode.Dynamic, content = content)
}
