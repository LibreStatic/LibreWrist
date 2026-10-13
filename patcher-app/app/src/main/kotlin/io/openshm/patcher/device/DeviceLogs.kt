package io.openshm.patcher.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.device.logs.DeviceLogRunner
import io.openshm.patcher.device.logs.DeviceLogSource
import io.openshm.patcher.device.logs.LogBuffer
import io.openshm.patcher.device.logs.LogExport
import io.openshm.patcher.device.logs.PackageTargets
import io.openshm.patcher.device.logs.PhoneLogSource
import io.openshm.patcher.device.logs.RunnerListener
import io.openshm.patcher.device.logs.WatchLogSource
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class LogDevice { Watch, Phone }

enum class DisconnectReason { WirelessDebuggingOff, NotPaired, KeyNotTrusted, Error }

sealed interface LogLinkStatus {
    /** Capture not started. */
    data object Idle : LogLinkStatus
    data object Connecting : LogLinkStatus
    data object Streaming : LogLinkStatus
    data object Reconnecting : LogLinkStatus
    data class Disconnected(val reason: DisconnectReason) : LogLinkStatus
}

data class DeviceLogState(
    val device: LogDevice,
    val status: LogLinkStatus = LogLinkStatus.Idle,
    val lineCount: Long = 0,
    /** Monitored packages actually installed. */
    val packages: List<String> = emptyList(),
    /** The last ~2000 lines, for the UI. */
    val tail: List<String> = emptyList(),
    /** For example "SM-L320 · Android 15". */
    val deviceLabel: String? = null,
)

data class DeviceLogsState(
    val capturing: Boolean = false,
    val watch: DeviceLogState = DeviceLogState(LogDevice.Watch),
    val phone: DeviceLogState = DeviceLogState(LogDevice.Phone),
)

/**
 * Process-wide capture of the watch's and the phone's logcat, filtered to the patched and patchable apps
 * of each device and buffered in files in the cache directory. [start] runs the capture in
 * [DeviceLogService] (a foreground service, so it goes on in the background); [stop] ends it and keeps
 * the buffers; [exportTo] writes both buffers into one text file.
 */
object DeviceLogs {
    private const val DIR = "device-logs"
    private const val PUBLISH_INTERVAL_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DeviceLogsState())
    val state: StateFlow<DeviceLogsState> = _state.asStateFlow()

    private val lock = Any()
    private var appContext: Context? = null
    private var captureJob: Job? = null
    private val buffers = HashMap<LogDevice, LogBuffer>()
    private val runners = HashMap<LogDevice, DeviceLogRunner>()

    /** Starts the capture of both devices through the foreground service. Idempotent. */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) { appContext = app }
        _state.update { it.copy(capturing = true) }
        DeviceLogService.start(app)
    }

    /** Stops the capture and the service. The buffers are kept. */
    fun stop(context: Context) {
        endCapture()
        context.applicationContext.stopService(Intent(context.applicationContext, DeviceLogService::class.java))
    }

    /** Reconnects [device] now (also done automatically when [AdbPresence] sees it on the network). */
    fun retry(device: LogDevice) {
        synchronized(lock) { runners[device] }?.retry()
    }

    /**
     * Pairs this phone with its own Wireless debugging through the shared pairing flow, then reconnects the
     * phone capture. Returns quietly when another pairing attempt is under way.
     */
    fun pairPhone(context: Context) {
        val app = context.applicationContext
        synchronized(lock) { appContext = app }
        if (!SelfAdbController.begin(SelfAdbPurpose.PhoneLog)) return
        SelfAdbService.start(app)
        scope.launch {
            val done = withTimeoutOrNull(SelfAdbService.TIMEOUT_MS + 5_000) {
                SelfAdbController.state.first {
                    (it.purpose == SelfAdbPurpose.PhoneLog && it.phase == SelfAdbPhase.Granting) || !it.active
                }
            }
            if (done != null && done.active && done.purpose == SelfAdbPurpose.PhoneLog) {
                SelfAdbController.finish() // the key is trusted now; the service closes the pairing connection
                delay(1_000)
                retry(LogDevice.Phone)
            }
        }
    }

    /** Truncates both buffers and resets the counts and tails. */
    fun clear() {
        for (device in LogDevice.entries) bufferFor(device)?.clear()
        _state.update {
            it.copy(
                watch = it.watch.copy(lineCount = 0, tail = emptyList()),
                phone = it.phone.copy(lineCount = 0, tail = emptyList()),
            )
        }
    }

    /** Writes both devices' logs into the document at [uri]. */
    suspend fun exportTo(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        synchronized(lock) { appContext = app }
        synchronized(lock) { buffers.values.toList() }.forEach { it.flush() }
        val now = System.currentTimeMillis()
        val serial = runCatching { AppCatalogue.current.serial }.getOrDefault(0L)
        val current = _state.value
        val sections = listOf(
            LogExport.Section("WATCH", current.watch.deviceLabel, fileFor(app, LogDevice.Watch)),
            LogExport.Section("PHONE", current.phone.deviceLabel, fileFor(app, LogDevice.Phone)),
        )
        val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not open the export file")
        out.use { LogExport.write(it, LogExport.header(BuildConfig.VERSION_NAME, now, serial), sections) }
    }

    fun exportFileName(nowMillis: Long = System.currentTimeMillis()): String = LogExport.fileName(nowMillis)

    // ---- capture, driven by DeviceLogService ----

    /** Starts the capture coroutines; a second call while one runs does nothing. */
    internal fun beginCapture(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            if (captureJob?.isActive == true) return
            _state.update { it.copy(capturing = true) }
            captureJob = scope.launch { capture(app) }
        }
    }

    /** Ends the capture (the service may already be gone). */
    internal fun endCapture() {
        val job = synchronized(lock) { captureJob.also { captureJob = null } }
        _state.update { it.copy(capturing = false) }
        job?.cancel()
    }

    private suspend fun capture(app: Context) {
        val watch = LogDevice.Watch
        val phone = LogDevice.Phone
        val runnerFor = mapOf(
            watch to newRunner(watch, WatchLogSource(WatchLink.get(app)), bufferFor(watch, app)!!),
            phone to newRunner(phone, PhoneLogSource(app), bufferFor(phone, app)!!),
        )
        synchronized(lock) { runners.putAll(runnerFor) }
        try {
            coroutineScope {
                launch { runnerFor.getValue(watch).run() }
                launch { runnerFor.getValue(phone).run() }
                launch {
                    while (true) {
                        publish()
                        delay(PUBLISH_INTERVAL_MS)
                    }
                }
                launch {
                    AdbPresence.state.collect { presence ->
                        val now = _state.value
                        if (presence.watchAvailable && now.watch.status is LogLinkStatus.Disconnected) runnerFor.getValue(watch).retry()
                        if (presence.phoneAvailable && now.phone.status is LogLinkStatus.Disconnected) runnerFor.getValue(phone).retry()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                synchronized(lock) { runners.clear() }
                publish()
                synchronized(lock) { buffers.values.toList() }.forEach { it.flush() }
                _state.update {
                    it.copy(
                        capturing = false,
                        watch = it.watch.copy(status = LogLinkStatus.Idle),
                        phone = it.phone.copy(status = LogLinkStatus.Idle),
                    )
                }
            }
        }
    }

    private fun newRunner(device: LogDevice, source: DeviceLogSource, buffer: LogBuffer) = DeviceLogRunner(
        source = source,
        targets = { PackageTargets.of(AppCatalogue.current, device, SetupSession.state.value.packagePrefix) },
        sink = buffer,
        listener = object : RunnerListener {
            override fun status(status: LogLinkStatus) = update(device) { it.copy(status = status) }
            override fun label(label: String?) = update(device) { it.copy(deviceLabel = label ?: it.deviceLabel) }
            override fun packages(packages: List<String>) = update(device) { it.copy(packages = packages) }
        },
    )

    private val published = HashMap<LogDevice, Long>()

    /** Pushes line counts and tails of buffers that changed, and flushes the ones due. */
    private fun publish() {
        for (device in LogDevice.entries) {
            val buffer = synchronized(lock) { buffers[device] } ?: continue
            buffer.flushIfDue()
            if (published[device] == buffer.revision) continue
            published[device] = buffer.revision
            val count = buffer.lineCount
            val tail = buffer.tail()
            update(device) { it.copy(lineCount = count, tail = tail) }
        }
    }

    private fun update(device: LogDevice, change: (DeviceLogState) -> DeviceLogState) {
        _state.update {
            if (device == LogDevice.Watch) it.copy(watch = change(it.watch)) else it.copy(phone = change(it.phone))
        }
    }

    private fun bufferFor(device: LogDevice, context: Context? = null): LogBuffer? = synchronized(lock) {
        buffers[device] ?: (context ?: appContext)?.let { ctx ->
            LogBuffer(fileFor(ctx, device)).also { buffers[device] = it }
        }
    }

    private fun fileFor(context: Context, device: LogDevice): File =
        File(File(context.cacheDir, DIR), if (device == LogDevice.Watch) "watch.txt" else "phone.txt")
}
