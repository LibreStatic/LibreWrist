package io.openshm.patcher.ui.screens.build

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.InputStore
import io.openshm.patcher.data.effectiveChinaMode
import io.openshm.patcher.data.sourceInputId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the Build screen gets an app icon from; null means "use the generic glyph". */
fun interface AppIconLoader {
    suspend fun load(outputId: String): ImageBitmap?
}

/** Provided by the Build destination; previews and tests leave the default (no icons) or install a fake. */
val LocalAppIconLoader = staticCompositionLocalOf { AppIconLoader { null } }

/**
 * Reads the icon of a patched output from the official input APK in app storage (the patches never
 * change it). Own apps have no input and give null. Decoding runs on the IO dispatcher and a found
 * icon is cached per output id.
 */
class ApkIconLoader(private val context: Context) : AppIconLoader {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    override suspend fun load(outputId: String): ImageBitmap? {
        // keyed by the input too: China mode changes which official APK the icon is read from
        val inputId = AppCatalogue.current.output(outputId).sourceInputId(effectiveChinaMode()) ?: return null
        val key = "$outputId|$inputId"
        cache[key]?.let { return it }
        if (key in missing) return null
        val icon = withContext(Dispatchers.IO) { read(inputId) }
        if (icon != null) cache[key] = icon else missing += key
        return icon
    }

    private fun read(inputId: String): ImageBitmap? = try {
        val file = InputStore(context).file(inputId)
        if (!file.isFile) return null
        val path = file.path
        val pm = context.packageManager
        val app = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
        // The archive info needs the paths set by hand before it can load resources.
        app.sourceDir = path
        app.publicSourceDir = path
        app.loadIcon(pm).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
    } catch (e: Exception) {
        null // an unreadable file only costs the icon
    }

    private companion object {
        const val ICON_PX = 144
    }
}
