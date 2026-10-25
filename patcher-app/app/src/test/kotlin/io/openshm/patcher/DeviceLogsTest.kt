package io.openshm.patcher

import io.openshm.patcher.adb.AdbService
import io.openshm.patcher.adb.AdbServiceType
import io.openshm.patcher.device.AdbPresence
import io.openshm.patcher.device.AdbPresenceState
import io.openshm.patcher.device.DisconnectReason
import io.openshm.patcher.device.LogDevice
import io.openshm.patcher.device.LogLinkStatus
import io.openshm.patcher.device.holdFalse
import io.openshm.patcher.device.logs.DeviceLogRunner
import io.openshm.patcher.device.logs.DeviceLogSource
import io.openshm.patcher.device.logs.JournalReader
import io.openshm.patcher.device.logs.LogBuffer
import io.openshm.patcher.device.logs.LogCommands
import io.openshm.patcher.device.logs.LogExport
import io.openshm.patcher.device.logs.LogFilter
import io.openshm.patcher.device.logs.LogLinkException
import io.openshm.patcher.device.logs.LogSession
import io.openshm.patcher.device.logs.LogShellResult
import io.openshm.patcher.device.logs.PackageTargets
import io.openshm.patcher.device.logs.ResumeCursor
import io.openshm.patcher.device.logs.RunnerListener
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneOffset
import java.util.TimeZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackageTargetsTest {
    private companion object {
        const val WEAR_SHEALTH = "com.samsung.android.wear.shealth"
    }

    private val catalogue = TestCatalogue.load()

    @Test
    fun `pm output is parsed and malformed lines are ignored`() {
        val parsed = PackageTargets.parsePackageUids(
            """
            package:com.a.b uid:10123
            package:com.shared uid:1000,10001
            package:broken
            package:bad name uid:10
            package:com.c uid:abc
            package:com.d uid:-5
            garbage
            package:com.e uid:10200
            """.trimIndent(),
        )
        assertEquals(mapOf("com.a.b" to listOf(10123), "com.shared" to listOf(1000, 10001), "com.e" to listOf(10200)), parsed)
    }

    @Test
    fun `watch targets are the watch inputs and outputs plus wear shealth`() {
        val targets = PackageTargets.of(catalogue, LogDevice.Watch, "abc.def")
        assertTrue("com.samsung.android.shealthmonitor" in targets.exact)
        assertTrue("com.samsung.android.watch.alarm" in targets.exact)
        assertTrue("com.samsung.android.shealthmonitors" in targets.exact)
        assertTrue("abc.def.shmbridge.watch" in targets.exact)
        assertTrue(WEAR_SHEALTH in targets.exact)
        assertFalse("abc.def.shmbridge.phone" in targets.exact)
        assertFalse("com.sec.android.app.shealth" in targets.exact)
    }

    @Test
    fun `phone targets exclude watch apps`() {
        val targets = PackageTargets.of(catalogue, LogDevice.Phone, "abc.def")
        assertTrue("abc.def.shmbridge.phone" in targets.exact)
        assertTrue("com.samsung.android.shealthmonitor" in targets.exact)
        assertFalse("abc.def.shmbridge.watch" in targets.exact)
        assertFalse(WEAR_SHEALTH in targets.exact)
        assertFalse("com.samsung.android.watch.alarm" in targets.exact)
    }

    @Test
    fun `selection keeps only installed targets and catches a foreign prefix`() {
        val targets = PackageTargets.of(catalogue, LogDevice.Watch, "abc.def")
        val installed = mapOf(
            "com.samsung.android.shealthmonitors" to listOf(10100),
            "other.prefix.shmbridge.watch" to listOf(10101),
            "abc.def.shmbridge.phone" to listOf(10102), // phone-only app
            "com.unrelated" to listOf(10103),
            WEAR_SHEALTH to listOf(10104),
        )
        assertEquals(
            setOf("com.samsung.android.shealthmonitors", "other.prefix.shmbridge.watch", WEAR_SHEALTH),
            targets.select(installed).keys,
        )
        assertTrue(targets.select(emptyMap()).isEmpty())
    }

    @Test
    fun `a template regex does not match a longer or foreign suffix`() {
        val regex = PackageTargets.templateRegex("\${PKG_PREFIX}.shmbridge.watch")!!
        assertTrue(regex.matches("a.b.shmbridge.watch"))
        assertFalse(regex.matches("a.b.shmbridge.watchx"))
        assertFalse(regex.matches("a.b.shmbridgeXwatch"))
        assertNull(PackageTargets.templateRegex("com.fixed"))
    }
}

class LogCommandsTest {
    @Test
    fun `uid command lists sorted numeric uids`() {
        assertEquals(
            "logcat -v threadtime -b main,system,crash --uid=10001,10123",
            LogCommands.forUids(listOf(10123, 10001)),
        )
    }

    @Test
    fun `since uses the validated timestamp and rejects free text`() {
        assertEquals(
            "logcat -v threadtime -b main,system,crash --uid=1000 -T '10-12 12:00:01.123'",
            LogCommands.system("10-12 12:00:01.123"),
        )
        for (bad in listOf("10-12 12:00:01", "'; rm -rf /; '", "10-12 12:00:01.123; id", "")) {
            try {
                LogCommands.system(bad)
                throw AssertionError("accepted: $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `no uids or negative uids are rejected`() {
        for (bad in listOf(emptyList(), listOf(-1))) {
            try {
                LogCommands.forUids(bad)
                throw AssertionError("accepted")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `threadtime lines yield timestamp and pid`() {
        val line = "10-12 12:00:01.123  1234  1250 I ActivityManager: Start proc com.x"
        assertEquals("10-12 12:00:01.123", LogCommands.timestampOf(line))
        assertEquals(1234, LogCommands.pidOf(line))
        assertNull(LogCommands.timestampOf("--------- beginning of main"))
    }

    @Test
    fun `pidof command only takes plain package names`() {
        assertEquals("pidof a.b c.d", LogCommands.pidof(listOf("a.b", "c.d")))
        try {
            LogCommands.pidof(listOf("a; id"))
            throw AssertionError("accepted")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun `system lines are kept only when they name a target package`() {
        val packages = setOf("com.samsung.android.shealthmonitors")
        assertTrue(LogFilter.keepSystemLine("I ActivityManager: Process com.samsung.android.shealthmonitors (pid 5) has died", packages))
        assertFalse(LogFilter.keepSystemLine("I ActivityManager: Start proc com.other", packages))
    }

    @Test
    fun `resume cursor drops the overlap of a restart once`() {
        val cursor = ResumeCursor()
        val a = "10-12 12:00:01.000  1  1 I T: a"
        val b = "10-12 12:00:02.000  1  1 I T: b"
        val c = "10-12 12:00:02.000  1  1 I T: c"
        assertTrue(cursor.accept(a))
        assertTrue(cursor.accept(b))
        assertEquals("10-12 12:00:02.000", cursor.since)
        cursor.resume()
        assertFalse(cursor.accept(b)) // logcat -T repeats the line at the cut
        assertTrue(cursor.accept(c))
        assertTrue(cursor.accept(b)) // a genuine later repeat is kept
    }
}

class LogBufferTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `append writes lines, counts them and keeps the newest in the tail`() {
        val buffer = LogBuffer(File(tmp.root, "x/w.txt"), tailSize = 3)
        (1..5).forEach { buffer.append("line $it") }
        buffer.flush()
        assertEquals(5, buffer.lineCount)
        assertEquals(listOf("line 3", "line 4", "line 5"), buffer.tail())
        assertEquals((1..5).map { "line $it" }, buffer.file.readLines())
    }

    @Test
    fun `an over-cap file keeps its newest half on line boundaries`() {
        val buffer = LogBuffer(File(tmp.root, "w.txt"), maxBytes = 200)
        (1..100).forEach { buffer.append("line %03d".format(it)) } // 9 bytes each with the newline
        buffer.flush()
        val lines = buffer.file.readLines()
        assertTrue(buffer.file.length() <= 200)
        assertEquals("line 100", lines.last())
        assertTrue(lines.all { it.matches(Regex("line \\d{3}")) })
        assertEquals(lines.size.toLong(), buffer.lineCount)
        assertEquals("line 100", buffer.tail().last())
    }

    @Test
    fun `clear empties the file, the count and the tail`() {
        val buffer = LogBuffer(File(tmp.root, "w.txt"))
        buffer.append("a")
        buffer.flush()
        buffer.clear()
        assertEquals(0, buffer.lineCount)
        assertTrue(buffer.tail().isEmpty())
        assertFalse(buffer.file.exists())
        buffer.append("b")
        buffer.flush()
        assertEquals(listOf("b"), buffer.file.readLines())
    }

    @Test
    fun `an existing file is picked up`() {
        val file = File(tmp.root, "w.txt").also { it.writeText("old 1\nold 2\n") }
        val buffer = LogBuffer(file)
        assertEquals(2, buffer.lineCount)
        buffer.append("new")
        buffer.flush()
        assertEquals(listOf("old 1", "old 2", "new"), buffer.tail())
        assertEquals(3, file.readLines().size)
    }

    @Test
    fun `lines are flushed after the interval without a timer`() {
        var now = 1_000L
        val buffer = LogBuffer(File(tmp.root, "w.txt"), now = { now })
        buffer.append("first") // flushes: the first write is older than the interval
        now += 100
        buffer.append("second")
        assertEquals(listOf("first"), buffer.file.readLines())
        now += 600
        buffer.flushIfDue()
        assertEquals(listOf("first", "second"), buffer.file.readLines())
    }

    @Test
    fun `export has the header and both sections, with a placeholder for a missing log`() {
        val watch = File(tmp.root, "watch.txt").also { it.writeText("w1\nw2\n") }
        val out = ByteArrayOutputStream()
        val zone = TimeZone.getTimeZone("UTC")
        val header = LogExport.header("0.2.0", 0L, 18, zone)
        LogExport.write(
            out, header,
            listOf(
                LogExport.Section("WATCH", "SM-L320 · Android 15", watch),
                LogExport.Section("PHONE", null, File(tmp.root, "missing.txt")),
            ),
        )
        assertEquals(
            "LibreWrist 0.2.0 — device logs\nExported: 1970-01-01T00:00:00Z\nCatalogue serial: 18\n" +
                "\n===== WATCH (SM-L320 · Android 15) =====\nw1\nw2\n" +
                "\n===== PHONE (unknown) =====\n${LogExport.NOT_CONNECTED}\n",
            out.toString("UTF-8"),
        )
        assertEquals("openshm-logs-19700101-000000.txt", LogExport.fileName(0L, zone))
    }
}

class AdbPresenceTest {
    private fun service(host: String, name: String = "adb-x-1", port: Int = 5555) =
        AdbService(name, host, port, AdbServiceType.CONNECT)

    private val own = { host: String -> host == "10.0.0.2" }

    @Test
    fun `the watch matches by remembered host or by serial prefix, never the phone itself`() {
        assertTrue(AdbPresence.match(listOf(service("10.0.0.9")), "10.0.0.9", null, own).watchAvailable)
        assertTrue(AdbPresence.match(listOf(service("10.0.0.77", "adb-R5-abc")), "10.0.0.9", "adb-R5-", own).watchAvailable)
        assertFalse(AdbPresence.match(listOf(service("10.0.0.5")), "10.0.0.9", "adb-R5-", own).watchAvailable)
        assertFalse(AdbPresence.match(listOf(service("10.0.0.2", "adb-R5-abc")), "10.0.0.2", "adb-R5-", own).watchAvailable)
        assertFalse(AdbPresence.match(listOf(service("10.0.0.9")), null, null, own).watchAvailable)
    }

    @Test
    fun `the phone is available when a connect service is on an own address`() {
        assertTrue(AdbPresence.match(listOf(service("10.0.0.2")), null, null, own).phoneAvailable)
        assertFalse(AdbPresence.match(listOf(service("10.0.0.9")), null, null, own).phoneAvailable)
        assertEquals(AdbPresenceState(), AdbPresence.match(emptyList(), "10.0.0.9", "p-", own))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `going away is held for the debounce while appearing is immediate`() = runTest {
        val source = MutableSharedFlow<AdbPresenceState>()
        val seen = mutableListOf<AdbPresenceState>()
        backgroundScope.launch { source.holdFalse(10_000).collect { seen += it } }
        runCurrent()
        val on = AdbPresenceState(watchAvailable = true)
        source.emit(on)
        runCurrent()
        assertEquals(listOf(on), seen)
        source.emit(AdbPresenceState())
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(listOf(on), seen)
        source.emit(on) // came back inside the hold
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(listOf(on), seen)
        source.emit(AdbPresenceState())
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf(on, AdbPresenceState()), seen)
    }
}

private class FakeSession(
    val onStream: suspend (String, (String) -> Unit) -> Unit,
    val installed: String = "package:com.samsung.android.shealthmonitors uid:10123\npackage:com.other uid:10999\n",
    val pidof: String = "",
    var journal: String = "",
) : LogSession {
    val streams = mutableListOf<String>()
    var closed = false

    override suspend fun shell(command: String): LogShellResult = when {
        command.startsWith("pm list") -> LogShellResult(0, installed)
        command.startsWith("getprop ro.product.model") -> LogShellResult(0, "SM-L320\n")
        command.startsWith("getprop ro.build.version.release") -> LogShellResult(0, "15\n")
        command.startsWith("pidof") -> LogShellResult(0, pidof)
        command.startsWith("cat /sdcard/Android/data/") -> LogShellResult(0, journal)
        else -> LogShellResult(1, "")
    }

    override suspend fun stream(command: String, onLine: (String) -> Unit) {
        streams += command
        onStream(command, onLine)
    }

    override fun close() {
        closed = true
    }
}

private class FakeSource(private val outcomes: ArrayDeque<() -> LogSession>) : DeviceLogSource {
    var connects = 0
    var resets = 0

    override suspend fun connect(): LogSession {
        connects++
        return outcomes.removeFirst()()
    }

    override fun reset() {
        resets++
    }
}

private class Recorder : RunnerListener {
    val statuses = mutableListOf<LogLinkStatus>()
    var label: String? = null
    var packages: List<String> = emptyList()
    override fun status(status: LogLinkStatus) {
        statuses += status
    }

    override fun label(label: String?) {
        this.label = label
    }

    override fun packages(packages: List<String>) {
        this.packages = packages
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceLogRunnerTest {
    private val targets = { PackageTargets(setOf("com.samsung.android.shealthmonitors"), emptyList()) }
    private val fail = { throw LogLinkException(DisconnectReason.WirelessDebuggingOff) }

    @Test
    fun `three failed attempts disconnect and a retry recovers`() = runTest {
        val session = FakeSession({ _, _ -> awaitCancellation() })
        val source = FakeSource(ArrayDeque(listOf<() -> LogSession>(fail, fail, fail, { session })))
        val listener = Recorder()
        val runner = DeviceLogRunner(source, targets, {}, listener, readJournal = false)
        backgroundScope.launch { runner.run() }
        runCurrent()
        assertEquals(listOf(LogLinkStatus.Connecting, LogLinkStatus.Reconnecting), listener.statuses)
        advanceTimeBy(6_001)
        advanceTimeBy(12_001)
        runCurrent()
        assertEquals(LogLinkStatus.Disconnected(DisconnectReason.WirelessDebuggingOff), listener.statuses.last())
        assertEquals(3, source.connects)
        runner.retry()
        runCurrent()
        assertEquals(LogLinkStatus.Streaming, listener.statuses.last())
        assertEquals("SM-L320 · Android 15", listener.label)
        assertEquals(listOf("com.samsung.android.shealthmonitors"), listener.packages)
    }

    @Test
    fun `a not paired device disconnects at once`() = runTest {
        val source = FakeSource(ArrayDeque(listOf<() -> LogSession>({ throw LogLinkException(DisconnectReason.NotPaired) })))
        val listener = Recorder()
        backgroundScope.launch { DeviceLogRunner(source, targets, {}, listener, readJournal = false).run() }
        runCurrent()
        assertEquals(LogLinkStatus.Disconnected(DisconnectReason.NotPaired), listener.statuses.last())
    }

    @Test
    fun `a stream that ends reconnects after the wait and resumes without repeating history`() = runTest {
        val l1 = "10-12 12:00:01.000  1  1 I T: one"
        val l2 = "10-12 12:00:02.000  1  1 I T: two"
        val l3 = "10-12 12:00:03.000  1  1 I T: three"
        val first = FakeSession({ cmd, emit -> if (cmd.contains("--uid=10123")) { emit(l1); emit(l2) } else awaitCancellation() })
        val second = FakeSession({ cmd, emit -> if (cmd.contains("--uid=10123")) { emit(l2); emit(l3); awaitCancellation() } else awaitCancellation() })
        val source = FakeSource(ArrayDeque(listOf<() -> LogSession>({ first }, { second })))
        val listener = Recorder()
        val lines = mutableListOf<String>()
        backgroundScope.launch { DeviceLogRunner(source, targets, { lines += it }, listener, readJournal = false).run() }
        runCurrent()
        assertEquals(LogLinkStatus.Reconnecting, listener.statuses.last())
        assertTrue(first.closed)
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(LogLinkStatus.Streaming, listener.statuses.last())
        assertEquals(1, source.resets)
        assertEquals(listOf(l1, l2, l3), lines)
        assertTrue(second.streams.any { it == "logcat -v threadtime -b main,system,crash --uid=10123 -T '10-12 12:00:02.000'" })
    }

    @Test
    fun `the night journal is read at start and again later, each entry once`() = runTest {
        val session = FakeSession({ _, _ -> awaitCancellation() }, journal = "1761264000000 process start\nnot a line\n")
        val source = FakeSource(ArrayDeque(listOf<() -> LogSession>({ session })))
        val lines = mutableListOf<String>()
        val runner = DeviceLogRunner(source, targets, { lines += it }, Recorder(), journals = JournalReader(ZoneOffset.UTC))
        backgroundScope.launch { runner.run() }
        runCurrent()
        assertEquals(listOf("spo2-journal com.samsung.android.shealthmonitors 2025-10-24 00:00:00.000 process start"), lines)
        session.journal = "1761264000000 process start\n1761264060500 register #1a\n"
        advanceTimeBy(600_001)
        runCurrent()
        assertEquals("spo2-journal com.samsung.android.shealthmonitors 2025-10-24 00:01:00.500 register #1a", lines.last())
        assertEquals(2, lines.size)
    }

    @Test
    fun `a capture without a journal says why once, and the phone reads none`() = runTest {
        val error = "cat: '/sdcard/Android/data/com.samsung.android.shealthmonitors/files/*spo2/events.log': No such file or directory"
        val watch = FakeSession({ _, _ -> awaitCancellation() }, journal = error)
        val lines = mutableListOf<String>()
        backgroundScope.launch {
            DeviceLogRunner(FakeSource(ArrayDeque(listOf<() -> LogSession>({ watch }))), targets, { lines += it }, Recorder()).run()
        }
        runCurrent()
        advanceTimeBy(600_001)
        runCurrent()
        assertEquals(
            listOf("spo2-journal: no night journal found; com.samsung.android.shealthmonitors: $error (exit 0)"),
            lines,
        )

        val phone = FakeSession({ _, _ -> awaitCancellation() }, journal = "1761264000000 process start\n")
        val phoneLines = mutableListOf<String>()
        backgroundScope.launch {
            DeviceLogRunner(
                FakeSource(ArrayDeque(listOf<() -> LogSession>({ phone }))), targets, { phoneLines += it }, Recorder(),
                readJournal = false,
            ).run()
        }
        runCurrent()
        assertEquals(emptyList<String>(), phoneLines)
    }

    @Test
    fun `the journal command only takes a plain package name`() {
        assertEquals(
            "cat /sdcard/Android/data/com.example.app/files/*spo2/events.log 2>&1",
            LogCommands.journal("com.example.app"),
        )
        assertThrows(IllegalArgumentException::class.java) { LogCommands.journal("a; rm -rf /") }
    }

    @Test
    fun `system lines are kept only for the monitored packages`() = runTest {
        val keep = "10-12 12:00:01.000  500  500 I ActivityManager: Process com.samsung.android.shealthmonitors (pid 9) has died"
        val drop = "10-12 12:00:02.000  500  500 I ActivityManager: Process com.other (pid 8) has died"
        val session = FakeSession({ cmd, emit -> if (cmd.contains("--uid=1000")) { emit(keep); emit(drop) }; awaitCancellation() })
        val source = FakeSource(ArrayDeque(listOf<() -> LogSession>({ session })))
        val lines = mutableListOf<String>()
        backgroundScope.launch { DeviceLogRunner(source, targets, { lines += it }, Recorder(), readJournal = false).run() }
        runCurrent()
        assertEquals(listOf(keep), lines)
    }

    @Test
    fun `without installed apps it streams nothing and checks again later`() = runTest {
        var installed = ""
        val session = object : LogSession {
            val streams = mutableListOf<String>()
            override suspend fun shell(command: String) = LogShellResult(0, if (command.startsWith("pm")) installed else "")
            override suspend fun stream(command: String, onLine: (String) -> Unit) {
                streams += command
                awaitCancellation()
            }

            override fun close() = Unit
        }
        val listener = Recorder()
        backgroundScope.launch {
            DeviceLogRunner(FakeSource(ArrayDeque(listOf<() -> LogSession>({ session }))), targets, {}, listener).run()
        }
        runCurrent()
        assertEquals(LogLinkStatus.Streaming, listener.statuses.last())
        assertTrue(session.streams.isEmpty())
        installed = "package:com.samsung.android.shealthmonitors uid:10123\n"
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(2, session.streams.size)
    }

    @Test
    fun `a logcat without --uid falls back to the pids of the apps`() = runTest {
        val mine = "10-12 12:00:01.000  777  777 I App: mine"
        val other = "10-12 12:00:01.000  888  888 I App: other"
        val session = FakeSession(
            { cmd, emit ->
                when {
                    cmd.contains("--uid=10123") -> emit("logcat: Unrecognized option: --uid=10123")
                    cmd.contains("--uid=1000") -> awaitCancellation()
                    else -> { emit(mine); emit(other); awaitCancellation() }
                }
            },
            pidof = "777\n",
        )
        val lines = mutableListOf<String>()
        val source = FakeSource(ArrayDeque(listOf<() -> LogSession>({ session })))
        backgroundScope.launch { DeviceLogRunner(source, targets, { lines += it }, Recorder(), readJournal = false).run() }
        runCurrent()
        assertEquals(listOf(mine), lines)
        assertTrue(session.streams.any { it == "logcat -v threadtime -b main,system,crash" })
    }
}
