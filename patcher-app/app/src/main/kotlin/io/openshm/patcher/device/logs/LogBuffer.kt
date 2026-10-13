package io.openshm.patcher.device.logs

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What a capture writes lines to. */
fun interface LogSink {
    fun append(line: String)
}

/**
 * The on-disk log of one device: lines are appended to [file] (flushed at least every
 * [flushIntervalMillis]), the newest [tailSize] lines are also kept in memory for the UI, and a file
 * over [maxBytes] drops its oldest half. An existing file is picked up: the count and tail start
 * from what it holds. All methods are thread-safe.
 */
class LogBuffer(
    val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val tailSize: Int = TAIL_LINES,
    private val flushIntervalMillis: Long = 500,
    private val now: () -> Long = System::currentTimeMillis,
) : LogSink {
    private val tail = ArrayDeque<String>()
    private var writer: BufferedWriter? = null
    private var bytes = 0L
    private var lines = 0L
    private var lastFlush = 0L
    private var dirty = false

    /** Bumped by every change, so a poller can tell whether anything moved. */
    @Volatile
    var revision = 0L
        private set

    init {
        file.parentFile?.mkdirs()
        if (file.isFile) scanExisting()
    }

    val lineCount: Long @Synchronized get() = lines

    @Synchronized
    fun tail(): List<String> = tail.toList()

    @Synchronized
    override fun append(line: String) {
        val w = writer ?: open()
        w.write(line)
        w.write("\n")
        dirty = true
        bytes += utf8Length(line) + 1
        lines++
        tail.addLast(line)
        if (tail.size > tailSize) tail.removeFirst()
        revision++
        if (bytes > maxBytes) trim()
        if (now() - lastFlush >= flushIntervalMillis) flushNow()
    }

    /** Flushes when the last flush is older than the interval; call it from a timer so idle data lands too. */
    @Synchronized
    fun flushIfDue() {
        if (dirty && now() - lastFlush >= flushIntervalMillis) flushNow()
    }

    @Synchronized
    fun flush() {
        flushNow()
    }

    /** Empties the file, the count and the tail. */
    @Synchronized
    fun clear() {
        closeWriter()
        file.delete()
        bytes = 0
        lines = 0
        tail.clear()
        revision++
    }

    @Synchronized
    fun close() {
        closeWriter()
    }

    private fun flushNow() {
        writer?.flush()
        dirty = false
        lastFlush = now()
    }

    private fun open(): BufferedWriter {
        file.parentFile?.mkdirs()
        return BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).also { writer = it }
    }

    private fun closeWriter() {
        runCatching { writer?.close() }
        writer = null
        dirty = false
    }

    private fun scanExisting() {
        bytes = file.length()
        file.bufferedReader(Charsets.UTF_8).useLines { sequence ->
            for (line in sequence) {
                lines++
                tail.addLast(line)
                if (tail.size > tailSize) tail.removeFirst()
            }
        }
    }

    /** Keeps the newest half of the file, starting at a line boundary. */
    private fun trim() {
        closeWriter()
        val keepFrom = file.length() - maxBytes / 2
        val temp = File(file.path + ".tmp")
        var kept = 0L
        var keptBytes = 0L
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(maxOf(0L, keepFrom))
            if (keepFrom > 0) raf.readLine() // the partial line at the cut
            temp.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    keptBytes += n
                    for (i in 0 until n) if (buf[i] == NEWLINE) kept++
                }
            }
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        bytes = keptBytes
        lines = kept
        revision++
    }

    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 50L * 1024 * 1024
        const val TAIL_LINES = 2000
        private const val NEWLINE = '\n'.code.toByte()
    }
}
