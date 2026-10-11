package io.openshm.patcher.ui.screens.build

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.InputStore
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
        cache[outputId]?.let { return it }
        if (outputId in missing) return null
        val icon = withContext(Dispatchers.IO) { read(outputId) }
        if (icon != null) cache[outputId] = icon else missing += outputId
        return icon
    }

    private fun read(outputId: String): ImageBitmap? = try {
        val inputId = AppCatalogue.current.output(outputId).inputId ?: return null
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
