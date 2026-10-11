package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbService

/**
 * Reads the watch's Android version from the mDNS announcement of its Wireless debugging service.
 * Recent adbd adds TXT records such as `api=36` and `name=SM-R920` as soon as Wireless debugging is
 * on, before any pairing. An older adbd sends neither, which means "unknown" and is never guessed.
 */
object WatchAnnouncement {
    /** Galaxy Watch model numbers (SM-R..., SM-L...). Phones (SM-S, SM-A, SM-G, ...) must not match. */
    private val WATCH_MODEL = Regex("^SM-[RL]\\d{3}", RegexOption.IGNORE_CASE)
    private val LEADING_INT = Regex("^\\s*(\\d+)")

    /** The detected watch for these TXT [attributes], or null unless they name a Galaxy Watch and an API level. */
    fun parse(attributes: Map<String, String>): DetectedWatch? {
        val model = attributes["name"]?.trim()?.takeIf { WATCH_MODEL.containsMatchIn(it) } ?: return null
        val sdk = attributes["api"]?.let { LEADING_INT.find(it) }?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        return DetectedWatch(sdk, release = null, source = DetectedWatch.Source.Network, model = model)
    }

    /** The first parsable service in [services] order (the discovery sorts by service name). */
    fun best(services: List<AdbService>): DetectedWatch? = services.firstNotNullOfOrNull { parse(it.attributes) }
}
