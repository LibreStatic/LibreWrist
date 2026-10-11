package io.openshm.patcher.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import java.util.TimeZone
import kotlinx.coroutines.delay

private const val MINUTE_MS = 60_000L
private const val DAY_MS = 24 * 60 * MINUTE_MS

/** Local wall-clock minutes since midnight at [nowMillis], with seconds as the fraction. */
internal fun minutesOfDay(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Float {
    val local = Math.floorMod(nowMillis + zone.getOffset(nowMillis), DAY_MS)
    return local / MINUTE_MS.toFloat()
}

/** "14:05" or, on a 12-hour phone, "2:05": no am/pm, it has to fit a small watch face. */
internal fun formatWatchTime(minutesOfDay: Int, is24Hour: Boolean): String {
    val hour = minutesOfDay / 60 % 24
    val minute = minutesOfDay % 60
    return if (is24Hour) "%02d:%02d".format(hour, minute) else "%d:%02d".format((hour + 11) % 12 + 1, minute)
}

/**
 * The phone's current time for the illustrated watch faces, updated on each minute. [fixed], when
 * set, is shown instead, so previews stay the same from one render to the next.
 */
@Composable
internal fun rememberWatchTime(fixed: String? = null): String {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val now = { formatWatchTime(minutesOfDay().toInt(), is24Hour) }
    return produceState(fixed ?: now(), fixed, is24Hour) {
        if (fixed != null) {
            value = fixed
            return@produceState
        }
        while (true) {
            value = now()
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
        }
    }.value
}
