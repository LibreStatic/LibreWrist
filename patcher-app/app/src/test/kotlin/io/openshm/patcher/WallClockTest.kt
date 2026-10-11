package io.openshm.patcher

import io.openshm.patcher.ui.components.formatWatchTime
import io.openshm.patcher.ui.components.minutesOfDay
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class WallClockTest {
    @Test fun minutesFollowTheLocalZone() {
        // 2026-10-11T12:30:30Z.
        val now = 1_791_721_830_000L
        assertEquals(12 * 60 + 30.5f, minutesOfDay(now, TimeZone.getTimeZone("UTC")), 0.001f)
        assertEquals(9 * 60 + 30.5f, minutesOfDay(now, TimeZone.getTimeZone("America/Argentina/Buenos_Aires")), 0.001f)
        // Wraps past midnight instead of going negative.
        assertEquals(17 * 60f, minutesOfDay(now - 27_030_000L, TimeZone.getTimeZone("Etc/GMT+12")), 0.001f)
    }

    @Test fun formatsForTwelveAndTwentyFourHourPhones() {
        assertEquals("14:05", formatWatchTime(14 * 60 + 5, is24Hour = true))
        assertEquals("2:05", formatWatchTime(14 * 60 + 5, is24Hour = false))
        assertEquals("00:07", formatWatchTime(7, is24Hour = true))
        assertEquals("12:07", formatWatchTime(7, is24Hour = false))
        assertEquals("12:00", formatWatchTime(12 * 60, is24Hour = false))
    }
}
