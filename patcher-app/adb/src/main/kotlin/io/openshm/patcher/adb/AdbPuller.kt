package io.openshm.patcher.adb

import java.io.OutputStream
import java.security.MessageDigest

/** A file copied off the device. */
data class PulledFile(val remotePath: String, val sizeBytes: Long, val sha256: String)

/**
 * Copies files off the device with `exec:cat`. The sync protocol is not used: `exec:` is a raw,
 * binary-safe stream and the size is checked against `stat`, which also catches the case where
 * `cat` prints an error instead of file content.
 */
class AdbPuller(private val client: AdbClient) {

    /** Remote paths of [pkg] (base APK and splits, as `pm path` prints them). */
    suspend fun pathsOf(pkg: String): List<String> {
        val paths = RemotePackages(client).pathsOf(pkg)
        if (paths.isEmpty()) throw AdbException.Protocol("Package is not installed: $pkg")
        return paths
    }

    /** Streams [remotePath] into [sink] and returns its size and SHA-256. [sink] is not closed. */
    suspend fun pull(
        remotePath: String,
        sink: OutputStream,
        timeoutMillis: Long = PULL_TIMEOUT_MS,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): PulledFile {
        val path = requireSafePath(remotePath)
        val statCmd = "stat -c %s '$path'"
        val expected = client.shell(statCmd).requireSuccess(statCmd).stdout.trim().toLongOrNull()
            ?: throw AdbException.Protocol("Unreadable file size for $path")

        return client.useChannel("exec:cat '$path'", timeoutMillis) { channel ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var copied = 0L
            while (true) {
                val n = channel.read(buf, 0, buf.size)
                if (n < 0) break
                digest.update(buf, 0, n)
                sink.write(buf, 0, n)
                copied += n
                if (copied > expected) throw AdbException.Protocol("$path is longer than its reported size")
                onProgress(copied, expected)
            }
            if (copied != expected) throw AdbException.Protocol("$path ended after $copied of $expected bytes")
            PulledFile(path, copied, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }

    /**
     * Pulls every APK of [pkg]. [sinkFor] gets each remote path and returns where to write it and
     * what to do once that file is complete (for example closing the stream).
     */
    suspend fun pullPackage(
        pkg: String,
        sinkFor: (remotePath: String) -> Pair<OutputStream, () -> Unit>,
        onProgress: (file: String, copied: Long, total: Long) -> Unit = { _, _, _ -> },
    ): List<PulledFile> = pathsOf(pkg).map { path ->
        val (sink, done) = sinkFor(path)
        try {
            pull(path, sink) { c, t -> onProgress(path, c, t) }
        } finally {
            done()
        }
    }

    companion object {
        const val PULL_TIMEOUT_MS = 10 * 60_000L
        private val SAFE_PATH = Regex("/[A-Za-z0-9_.=+~@%,-]+(/[A-Za-z0-9_.=+~@%,-]+)*")

        /** Rejects anything that is not a plain absolute path (no quotes, spaces or shell syntax). */
        fun requireSafePath(path: String): String {
            require(SAFE_PATH.matches(path)) { "Unsafe remote path: ${path.take(80)}" }
            return path
        }
    }
}
