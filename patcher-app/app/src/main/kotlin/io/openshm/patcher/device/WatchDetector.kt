package io.openshm.patcher.device

import io.openshm.patcher.data.WatchProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** What the watch reported about its Android version. */
data class DetectedWatch(val sdk: Int, val release: String?) {
    /** The alarm fork needs Android 14 (API 34), which is Wear OS 5. */
    val profile: WatchProfile get() = if (sdk >= MIN_SDK_WEAR_OS_5) WatchProfile.WearOs5 else WatchProfile.WearOs3Or4

    /** "14" when the watch reported it, else the API level. */
    val versionLabel: String get() = release ?: "API $sdk"

    companion object {
        const val MIN_SDK_WEAR_OS_5 = 34
    }
}

/** Reads the watch's Android version over the ADB link. */
object WatchDetector {
    const val COMMAND = "getprop ro.build.version.sdk; getprop ro.build.version.release"
    const val DEFAULT_TIMEOUT_MS = 3_000L

    private val RELEASE = Regex("[0-9][0-9.]*")

    /** Parses the output of [COMMAND] (SDK level on the first line, release on the second). */
    fun parse(stdout: String): DetectedWatch? {
        val lines = stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sdk = lines.firstOrNull()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val release = lines.getOrNull(1)?.takeIf { RELEASE.matches(it) }
        return DetectedWatch(sdk, release)
    }

    /**
     * The watch to correct the chosen profile for, or null when detection failed or already agrees
     * with [current].
     */
    fun correction(current: WatchProfile, detected: DetectedWatch?): DetectedWatch? =
        detected?.takeIf { it.profile != current }

    /** Runs the detection; null on any failure or when it takes longer than [timeoutMillis]. */
    suspend fun detect(link: WatchLink, timeoutMillis: Long = DEFAULT_TIMEOUT_MS): DetectedWatch? = try {
        withTimeoutOrNull(timeoutMillis) {
            val result = link.client().shell(COMMAND)
            if (result.isSuccess) parse(result.stdout) else null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
