package io.openshm.patcher.device.logs

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The logcat command lines and the small facts read from `threadtime` lines. Commands are built only
 * from validated numbers and timestamps; no device text is ever pasted into one.
 */
object LogCommands {
    private const val BASE = "logcat -v threadtime -b main,system,crash"

    /** `MM-DD HH:MM:SS.mmm`, the form `logcat -T` accepts and `threadtime` prints. */
    private val TIMESTAMP = Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")
    private val THREADTIME = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+[VDIWEFS]\s""")

    /** Everything the given uids log. */
    fun forUids(uids: Collection<Int>, since: String? = null): String {
        require(uids.isNotEmpty()) { "no uids" }
        require(uids.all { it >= 0 }) { "negative uid" }
        return "$BASE --uid=${uids.sorted().joinToString(",")}${sinceArg(since)}"
    }

    /** What `system_server` (uid 1000) logs; the caller keeps only the lines naming a target package. */
    fun system(since: String? = null): String = "$BASE --uid=1000${sinceArg(since)}"

    /** Fallback for a logcat without `--uid`: everything, filtered by pid on the phone. */
    fun everything(since: String? = null): String = "$BASE${sinceArg(since)}"

    /** `pidof` for validated package names. */
    fun pidof(packages: Collection<String>): String {
        require(packages.isNotEmpty() && packages.all { PACKAGE.matches(it) }) { "bad package name" }
        return "pidof ${packages.joinToString(" ")}"
    }

    /**
     * Prints the SpO2 adapter's night journal of a patched watch app (docs/spo2-adapter.md), kept in
     * its external files dir so the shell user can read it; prints nothing where there is none.
     */
    fun journal(pkg: String): String {
        require(PACKAGE.matches(pkg)) { "bad package name" }
        // The build rewrites the placeholder in the folder name (`<prefix>-spo2`), hence the glob.
        return "cat /sdcard/Android/data/$pkg/files/*spo2/events.log 2>/dev/null"
    }

    private val PACKAGE = Regex("[A-Za-z0-9_.]+")

    fun isValidTimestamp(value: String): Boolean = TIMESTAMP.matches(value)

    private fun sinceArg(since: String?): String {
        if (since == null) return ""
        require(isValidTimestamp(since)) { "bad timestamp" }
        return " -T '$since'"
    }

    /** The `MM-DD HH:MM:SS.mmm` of a `threadtime` line, or null (headers such as `--------- beginning of main`). */
    fun timestampOf(line: String): String? = THREADTIME.find(line)?.groupValues?.get(1)

    /** The pid of a `threadtime` line, or null. */
    fun pidOf(line: String): Int? = THREADTIME.find(line)?.groupValues?.get(2)?.toIntOrNull()

    /** The pids printed by `pidof` (space separated). */
    fun parsePids(output: String): Set<Int> =
        output.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }.filter { it > 0 }.toSet()

    /** True for a logcat that does not know `--uid`: an unknown-option error or its usage text. */
    fun isUsageError(line: String): Boolean = USAGE.containsMatchIn(line)

    private val USAGE = Regex("(?i)(unrecognized|unknown|invalid|illegal) option|^usage: logcat")
}

/** Which lines of the `system_server` stream are about the monitored apps. */
object LogFilter {
    fun keepSystemLine(line: String, packages: Collection<String>): Boolean = packages.any { line.contains(it) }
}

/**
 * Where a stream stopped, so a restart can ask logcat for `-T <timestamp>` without printing the same
 * history twice. `-T` includes the line at that very timestamp, so the lines already kept at it are
 * remembered and dropped once when the stream resumes.
 */
class ResumeCursor {
    private var lastTimestamp: String? = null
    private val atLast = LinkedHashSet<String>()
    private var skip: Set<String> = emptySet()

    /** The `-T` value for the next start, or null when no timestamped line was seen. */
    val since: String? get() = lastTimestamp

    /** Call when a new stream starts. */
    fun resume() {
        skip = atLast.toSet()
    }

    /** True when [line] is new and was recorded; false for a repeat of a line already kept. */
    fun accept(line: String): Boolean {
        val timestamp = LogCommands.timestampOf(line)
        if (skip.isNotEmpty()) {
            if (timestamp != null && timestamp != lastTimestamp) {
                skip = emptySet() // past the overlap: nothing left to drop
            } else if (line in skip) {
                skip = skip - line
                return false
            }
        }
        if (timestamp == null) return true
        if (timestamp != lastTimestamp) {
            lastTimestamp = timestamp
            atLast.clear()
        }
        atLast += line
        return true
    }
}

/**
 * Turns the night journal's `<epoch ms> <text>` lines into log lines with a readable local time, each
 * entry once: the journal is read again during a long capture and its file is rewritten when trimmed.
 */
class JournalReader(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val seen = LinkedHashSet<String>()

    /** The new entries of [output] as log lines, oldest first; unparsable lines are skipped. */
    fun newLines(pkg: String, output: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            val space = line.indexOf(' ')
            if (space <= 0) continue
            val epochMs = line.substring(0, space).toLongOrNull() ?: continue
            val key = "$pkg $line"
            if (!seen.add(key)) continue
            if (seen.size > MAX_SEEN) seen.remove(seen.first())
            val time = FORMAT.format(Instant.ofEpochMilli(epochMs).atZone(zone))
            out += "spo2-journal $pkg $time ${line.substring(space + 1).take(MAX_TEXT)}"
        }
        return out
    }

    private companion object {
        /** Above the journal's 400-line file limit, for every app it is read from. */
        const val MAX_SEEN = 2_000
        const val MAX_TEXT = 500
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }
}
