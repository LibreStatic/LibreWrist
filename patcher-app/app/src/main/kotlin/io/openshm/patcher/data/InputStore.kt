package io.openshm.patcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android side of the input import: files under `filesDir/inputs`, read through the content resolver. */
class InputStore(context: Context) {
    private val context = context.applicationContext
    private val files = InputFiles(File(this.context.filesDir, "inputs"))

    fun file(inputId: String): File = files.file(inputId)

    /** The verified size of [input]'s file, or null when it is missing or stale. */
    fun verifiedSize(input: InputApp): Long? = files.status(input)?.sizeBytes

    suspend fun import(input: InputApp, uri: Uri, onProgress: (Long, Long) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val total = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                }
            }.getOrNull() ?: -1L
            val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: return@withContext ImportResult.Rejected(RejectReason.Unreadable)
            stream.use { files.import(input, it, total, ::inspect, onProgress) }
        }

    @Suppress("DEPRECATION")
    private fun inspect(file: File): ArchiveInfo? =
        runCatching { context.packageManager.getPackageArchiveInfo(file.path, 0) }.getOrNull()
            ?.let { ArchiveInfo(it.packageName, it.versionName) }
}
