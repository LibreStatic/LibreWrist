package io.openshm.patcher.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the watch-version detection is: [watch] is set once a remembered watch answered. */
data class WatchDetectionState(val running: Boolean = false, val done: Boolean = false, val watch: DetectedWatch? = null)

/**
 * Process-wide watch-version detection, started as early as the welcome screen so the profile step
 * can be skipped when the remembered watch already answered. Outlives the screens that start it.
 */
object WatchDetection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(WatchDetectionState())
    val state: StateFlow<WatchDetectionState> = _state.asStateFlow()

    /** Asks the remembered watch, if there is one and no detection is under way; a known answer is kept. */
    fun start(link: WatchLink) = start(link.rememberedHost != null) { WatchDetector.detect(link) }

    /** [start] without Android: [eligible] says whether there is a watch to ask. A failed attempt may be retried. */
    internal fun start(eligible: Boolean, detect: suspend () -> DetectedWatch?) {
        if (!eligible) return
        while (true) {
            val current = _state.value
            if (current.running || current.watch != null) return
            if (_state.compareAndSet(current, current.copy(running = true))) break
        }
        scope.launch {
            val watch = runCatching { detect() }.getOrNull()
            _state.update { WatchDetectionState(running = false, done = true, watch = watch) }
        }
    }

    /** Forgets the answer, for tests. */
    internal fun reset() {
        _state.value = WatchDetectionState()
    }
}
