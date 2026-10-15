package io.openshm.patcher.adb

import java.io.InputStream

/** One APK to put in an install session. [open] is called once, on an IO thread. */
class ApkSource(val name: String, val sizeBytes: Long, val open: () -> InputStream) {
    init {
        require(NAME.matches(name)) { "APK entry name must match ${NAME.pattern}: ${name.take(60)}" }
        require(sizeBytes > 0) { "APK size must be positive" }
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}\\.apk")
    }
}

data class InstallOptions(
    /** `--user N`; null leaves the choice to the device (current user). */
    val userId: Int? = null,
    /** `-r`: replace an existing install. */
    val replaceExisting: Boolean = true,
    /** `-d`: allow a version downgrade. */
    val allowDowngrade: Boolean = false,
    /** `-g`: grant all runtime permissions declared in the manifest. */
    val grantRuntimePermissions: Boolean = false,
)

sealed class InstallResult {
    data class Success(val sessionId: Int) : InstallResult()

    /** [code] is the package manager's code, for example `INSTALL_FAILED_VERSION_DOWNGRADE`. */
    data class Failure(val code: String?, val message: String, val stage: Stage) : InstallResult()

    enum class Stage { CREATE, WRITE, COMMIT }
}

/** Streamed install through `cmd package install-*`, with multiple APKs per session. */
class AdbInstaller(private val client: AdbClient) {

    /**
     * Installs [apks] (base first, then splits) in one session. [onProgress] gets (bytes written, total).
     * On any failure after the session exists it is abandoned. Transport failures are thrown as
     * [AdbException]; package-manager refusals come back as [InstallResult.Failure].
     */
    suspend fun install(
        apks: List<ApkSource>,
        options: InstallOptions = InstallOptions(),
        writeTimeoutMillis: Long = WRITE_TIMEOUT_MS,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
    ): InstallResult {
        require(apks.isNotEmpty()) { "Nothing to install" }
        require(apks.map { it.name }.toSet().size == apks.size) { "Duplicate APK entry names" }
        val total = apks.sumOf { it.sizeBytes }

        val createCmd = InstallCommands.create(total, options)
        val created = client.shell(createCmd)
        val sessionId = InstallOutput.parseSessionId(created.stdout)
        if (!created.isSuccess || sessionId == null) {
            return InstallOutput.failure(created, InstallResult.Stage.CREATE)
        }

        var committed = false
        try {
            var written = 0L
            for (apk in apks) {
                val base = written
                val out = streamApk(sessionId, apk, writeTimeoutMillis) { onProgress(base + it, total) }
                if (!out.isSuccess || !InstallOutput.isSuccess(out.stdout)) {
                    return InstallOutput.failure(out, InstallResult.Stage.WRITE)
                }
                written += apk.sizeBytes
            }
            val commit = client.shell(InstallCommands.commit(sessionId), writeTimeoutMillis)
            if (!commit.isSuccess || !InstallOutput.isSuccess(commit.stdout)) {
                return InstallOutput.failure(commit, InstallResult.Stage.COMMIT)
            }
            committed = true
            return InstallResult.Success(sessionId)
        } finally {
            if (!committed) abandon(sessionId)
        }
    }

    private suspend fun streamApk(
        sessionId: Int,
        apk: ApkSource,
        timeoutMillis: Long,
        progress: (Long) -> Unit,
    ): ShellResult = client.useChannel(InstallCommands.write(sessionId, apk), timeoutMillis) { channel ->
        var sent = 0L
        apk.open().use { input ->
            // Each chunk goes out as one stdin packet: the shell-protocol header, then the bytes.
            val buf = ByteArray(ShellV2.HEADER_SIZE + 64 * 1024)
            val room = buf.size - ShellV2.HEADER_SIZE
            while (sent < apk.sizeBytes) {
                val n = input.read(buf, ShellV2.HEADER_SIZE, minOf(room.toLong(), apk.sizeBytes - sent).toInt())
                if (n < 0) throw AdbException.Protocol("${apk.name} is shorter than its declared size")
                ShellV2.putHeader(buf, ShellV2.ID_STDIN, n)
                channel.write(buf, 0, ShellV2.HEADER_SIZE + n)
                sent += n
                progress(sent)
            }
        }
        // `-S size` makes the device stop reading after exactly that many bytes, then exit. The
        // exit packet ends the read; nothing waits for the stream to close (see [InstallCommands.write]).
        ShellV2.readResult(channel)
    }

    private suspend fun abandon(sessionId: Int) {
        // Best effort, and it must still run when the install coroutine itself was cancelled.
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                client.shell(InstallCommands.abandon(sessionId), 15_000)
            }
        }
    }

    companion object {
        const val WRITE_TIMEOUT_MS = 10 * 60_000L
    }
}

internal object InstallCommands {
    fun create(totalBytes: Long, options: InstallOptions): String = buildString {
        append("cmd package install-create -S ").append(totalBytes)
        if (options.replaceExisting) append(" -r")
        if (options.allowDowngrade) append(" -d")
        if (options.grantRuntimePermissions) append(" -g")
        options.userId?.let {
            require(it in 0..9999) { "Invalid user id" }
            append(" --user ").append(it)
        }
    }

    /**
     * `shell,v2` without a pty: APK bytes pass untouched inside stdin packets, and the exit packet
     * tells when the command is done. Not `exec:`, whose only end marker is the stream closing:
     * libadb loses a close that arrives while output is still queued, and the read then never
     * returns (the device has received every byte, but the session is never committed).
     */
    fun write(sessionId: Int, apk: ApkSource): String =
        "shell,v2,raw:cmd package install-write -S ${apk.sizeBytes} $sessionId ${apk.name} -"

    fun commit(sessionId: Int) = "cmd package install-commit $sessionId"

    fun abandon(sessionId: Int) = "cmd package install-abandon $sessionId"
}

internal object InstallOutput {
    private val session = Regex("""created install session \[(\d+)]""")
    private val failure = Regex("""Failure\s*\[([^\]:]+)(?::\s*([^\]]*))?]""")

    fun parseSessionId(output: String): Int? =
        session.find(output)?.groupValues?.get(1)?.toIntOrNull()

    /** True when a line starts with `Success` and none starts with `Failure`/`Error`. */
    fun isSuccess(output: String): Boolean {
        val lines = output.lineSequence().map { it.trim() }.toList()
        return lines.any { it.startsWith("Success") } &&
            lines.none { it.startsWith("Failure") || it.startsWith("Error") || it.startsWith("Exception") }
    }

    fun failure(result: ShellResult, stage: InstallResult.Stage): InstallResult.Failure {
        val text = (result.stdout + "\n" + result.stderr).trim()
        val match = failure.find(text)
        val code = match?.groupValues?.get(1)?.trim()
        val message = match?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "exit code ${result.exitCode}"
        return InstallResult.Failure(code, message, stage)
    }
}
