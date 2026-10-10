package io.openshm.patcher.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * True when the user turned animations off (developer option or "Remove animations"):
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0`. Shape morphs and spins must then become
 * crossfades or stay still.
 */
val LocalReducedMotion = compositionLocalOf { false }

internal fun isReducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

private fun readAnimatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Tracks the system setting and re-reads it when the user changes it while the app is open. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(isReducedMotion(readAnimatorScale(context))) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = isReducedMotion(readAnimatorScale(context))
            }
        }
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        context.contentResolver.registerContentObserver(uri, false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return reduced
}
