package io.openshm.patcher.device

import android.content.Context
import io.openshm.patcher.adb.AdbDiscovery
import io.openshm.patcher.adb.AdbServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Process-wide listener for the watch's Wi-Fi announcement (see [WatchAnnouncement]), shared by the
 * screens that can use the answer. It runs while at least one holder (a screen) is started, keeps
 * listening so a later announcement still lands, and remembers the latest watch it saw. Any failure
 * of the discovery is silent: nothing is announced then.
 */
object WatchAnnouncements {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DetectedWatch?>(null)
    private val _running = MutableStateFlow(false)
    private val holders = mutableSetOf<String>()
    private var job: Job? = null

    /** The latest watch announced, or null when none was seen. */
    val state: StateFlow<DetectedWatch?> = _state.asStateFlow()

    /** True while listening. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** [holder] needs the listener. Idempotent per holder. */
    fun start(context: Context, holder: String) {
        val app = context.applicationContext
        start(holder) {
            val discovery = AdbDiscovery(app)
            merge(announced(discovery, AdbServiceType.CONNECT), announced(discovery, AdbServiceType.PAIRING))
        }
    }

    private fun announced(discovery: AdbDiscovery, type: AdbServiceType): Flow<DetectedWatch?> =
        discovery.discover(type).map(WatchAnnouncement::best).catch { }

    /** [start] without Android: [source] emits what each discovery update announced (null for nothing). */
    @Synchronized
    internal fun start(holder: String, source: () -> Flow<DetectedWatch?>) {
        holders += holder
        if (job?.isActive == true) return
        _running.value = true
        job = scope.launch {
            try {
                source().collect { watch -> if (watch != null) _state.value = watch }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Silent: the user simply picks.
            }
        }.also { started ->
            started.invokeOnCompletion { synchronized(this) { if (job === started) _running.value = false } }
        }
    }

    /** [holder] no longer needs the listener; it stops once nobody does. */
    @Synchronized
    fun stop(holder: String) {
        if (!holders.remove(holder) || holders.isNotEmpty()) return
        job?.cancel()
        job = null
        _running.value = false
    }

    /** Forgets everything, for tests. */
    @Synchronized
    internal fun reset() {
        holders.clear()
        job?.cancel()
        job = null
        _running.value = false
        _state.value = null
    }
}
