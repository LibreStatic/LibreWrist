package io.openshm.patcher.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.openshm.patcher.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Why an update was refused before or during its install. */
enum class AppUpdateError {
    DownloadFailed, TooLarge, HashMismatch, NotThisApp, WrongBuild, OtherSigner, Unreadable, InstallFailed,
}

sealed interface AppUpdateDownload {
    data class Ready(val file: File) : AppUpdateDownload
    data class Failed(val error: AppUpdateError) : AppUpdateDownload
}

/**
 * Downloads the update APK into the cache directory and verifies it before it may be installed: the
 * SHA-256 from the release's `SHA256SUMS`, then the archive itself must be this app (same package,
 * the announced build) signed by the same certificate as the running app. Everything else about the
 * transfer (https-only redirects, size cap, cancellation) is [ApkDownloader].
 */
class AppUpdateDownloader(context: Context) {
    private val context = context.applicationContext
    private val dir get() = File(context.cacheDir, DIR)

    /** Removes what an earlier run left behind (a finished install ends the process before it can clean up). */
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** Blocking; call off the main thread. [onOpen] receives a closer that aborts the transfer. */
    fun download(
        update: AppUpdate,
        onProgress: (Float) -> Unit,
        onOpen: (close: () -> Unit) -> Unit,
        onVerifying: () -> Unit = {},
    ): AppUpdateDownload {
        cleanup()
        if (!dir.mkdirs() && !dir.isDirectory) return AppUpdateDownload.Failed(AppUpdateError.DownloadFailed)
        val target = File(dir, update.apkName)
        val part = File(dir, update.apkName + ".part")
        try {
            val cap = minOf(update.sizeBytes + MARGIN_BYTES, MAX_BYTES)
            val request = DownloadRequest(update.apkUrl, "LibreWrist/${BuildConfig.VERSION_NAME}", null, null, update.sizeBytes)
            val result = ApkDownloader(maxBytes = cap).fetch(request, onOpen) { stream, _ ->
                var done = 0L
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress((done.toFloat() / update.sizeBytes).coerceIn(0f, 1f))
                    }
                }
                ImportResult.Verified(done)
            }
            if (result is ImportResult.Rejected) {
                return AppUpdateDownload.Failed(if (result.reason == RejectReason.TooLarge) AppUpdateError.TooLarge else AppUpdateError.DownloadFailed)
            }
            if (!part.renameTo(target)) return AppUpdateDownload.Failed(AppUpdateError.Unreadable)
            onVerifying()
            return verify(target, update)?.let { AppUpdateDownload.Failed(it) } ?: AppUpdateDownload.Ready(target)
        } catch (e: IOException) {
            return AppUpdateDownload.Failed(AppUpdateError.DownloadFailed)
        } finally {
            part.delete()
        }
    }

    /** Null when [file] may be installed over this app; the file is deleted when it may not. */
    private fun verify(file: File, update: AppUpdate): AppUpdateError? {
        val error = try {
            if (!fileHasSha256(file, update.sha256)) AppUpdateError.HashMismatch else archiveError(file, update)
        } catch (e: IOException) {
            AppUpdateError.Unreadable
        }
        if (error != null) file.delete()
        return error
    }

    private fun archiveError(file: File, update: AppUpdate): AppUpdateError? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return AppUpdateError.Unreadable
        if (archive.packageName != context.packageName) return AppUpdateError.NotThisApp
        if (archive.longVersionCode != update.build) return AppUpdateError.WrongBuild
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archiveSigners = signersOf(archive)
        return if (archiveSigners.isEmpty() || archiveSigners != signersOf(installed)) AppUpdateError.OtherSigner else null
    }

    private fun signersOf(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()

    private companion object {
        const val DIR = "self-update"
        const val BUFFER = 64 * 1024
        const val MARGIN_BYTES = 1024L * 1024
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
