package io.openshm.patcher.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Android side of the input import: files under `filesDir/inputs`, read through the content resolver. */
class InputStore(context: Context) {
    private val context = context.applicationContext
    private val files = InputFiles(File(this.context.filesDir, "inputs"))
    private val downloader = ApkDownloader()

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

    /**
     * Downloads [request] (https only) and verifies it like a picked file. Cancelling the caller closes
     * the connection; the partial file is deleted on any failure.
     */
    suspend fun download(input: InputApp, request: DownloadRequest, onProgress: (Long, Long) -> Unit): ImportResult =
        withContext(Dispatchers.IO) {
            val job = coroutineContext.job
            val close = AtomicReference<(() -> Unit)?>(null)
            // Cancelling the caller cancels this watcher, which closes the connection so a blocked read returns.
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    close.get()?.invoke()
                }
            }
            try {
                downloader.fetch(request, onOpen = { close.set(it) }) { stream, total ->
                    files.import(input, stream, total, ::inspect) { done, all ->
                        job.ensureActive()
                        onProgress(done, all)
                    }
                }
            } finally {
                watcher.cancel()
            }
        }

    @Suppress("DEPRECATION")
    private fun inspect(file: File): ArchiveInfo? =
        runCatching { context.packageManager.getPackageArchiveInfo(file.path, 0) }.getOrNull()
            ?.let { ArchiveInfo(it.packageName, it.versionName) }
}
