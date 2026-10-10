package io.openshm.patcher.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Why an imported file was not accepted. */
enum class RejectReason { WrongApp, WrongVersion, NotExpectedFile, Unreadable, DownloadFailed, TooLarge }

sealed interface ImportResult {
    data class Verified(val sizeBytes: Long) : ImportResult
    data class Rejected(val reason: RejectReason) : ImportResult
}

/** What a package archive says about itself (null from the inspector means "not an APK"). */
data class ArchiveInfo(val packageName: String?, val versionName: String?)

/** A verified input as recorded next to its file. */
data class VerifiedInput(val sizeBytes: Long)

/**
 * The input APKs the user supplied, kept as `<dir>/<inputId>.apk` with a `<inputId>.apk.verified`
 * record (`<sha256> <size>`). A file only counts when its record matches the catalogue hash, so a
 * catalogue update that changes a hash invalidates old files. Plain JVM code (no Android types).
 */
class InputFiles(private val dir: File) {
    fun file(inputId: String) = File(dir, "$inputId.apk")
    private fun record(inputId: String) = File(dir, "$inputId.apk.verified")
    private fun temp(inputId: String) = File(dir, "$inputId.apk.part")

    /** The verified size, or null; a stale or inconsistent file is deleted. */
    fun status(input: InputApp): VerifiedInput? {
        val file = file(input.id)
        val rec = record(input.id)
        if (!file.isFile && !rec.isFile) return null
        val parts = runCatching { rec.readText().trim().split(' ') }.getOrNull()
        val size = parts?.getOrNull(1)?.toLongOrNull()
        if (parts?.getOrNull(0) == input.sha256 && size != null && file.isFile && file.length() == size) {
            return VerifiedInput(size)
        }
        file.delete()
        rec.delete()
        return null
    }

    /**
     * Streams [source] into a temp file while hashing it. A hash equal to the catalogue's moves the
     * file into place atomically; anything else deletes it and says why, using [inspect] on the temp
     * file (the platform's package parser). [onProgress] gets (done, total); total is -1 when unknown.
     */
    fun import(
        input: InputApp,
        source: InputStream,
        totalBytes: Long,
        inspect: (File) -> ArchiveInfo?,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): ImportResult {
        val tmp = temp(input.id)
        try {
            dir.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var reported = 0L
            tmp.outputStream().use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    done += n
                    if (done - reported >= REPORT_STEP) {
                        reported = done
                        onProgress(done, totalBytes)
                    }
                }
            }
            onProgress(done, if (totalBytes > 0) totalBytes else done)
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != input.sha256.lowercase()) return ImportResult.Rejected(reasonFor(input, inspect(tmp)))
            record(input.id).delete()
            Files.move(tmp.toPath(), file(input.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            record(input.id).writeText("${input.sha256} $done")
            return ImportResult.Verified(done)
        } catch (e: IOException) {
            return ImportResult.Rejected(RejectReason.Unreadable)
        } finally {
            tmp.delete()
        }
    }

    private fun reasonFor(input: InputApp, info: ArchiveInfo?): RejectReason = when {
        info?.packageName == null -> RejectReason.NotExpectedFile
        info.packageName != input.packageName -> RejectReason.WrongApp
        info.versionName != input.versionName -> RejectReason.WrongVersion
        else -> RejectReason.NotExpectedFile
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val REPORT_STEP = 256L * 1024
    }
}
