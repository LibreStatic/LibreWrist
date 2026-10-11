package io.openshm.patcher

import io.openshm.patcher.ui.components.ecgShown
import io.openshm.patcher.ui.components.heartbeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroAnimationTest {
    @Test fun faceStartsOnTheTimeThenShowsTheEcg() {
        assertEquals(0f, ecgShown(0f), 0f)
        assertEquals(0f, ecgShown(3f), 0f)
        assertEquals(1f, ecgShown(4.5f), 0f)
        // Back on the time once the cycle wraps.
        assertEquals(0f, ecgShown(6.4f + 1f), 0f)
    }

    @Test fun heartbeatPulsesWithinRange() {
        val samples = (0 until 440).map { heartbeat(it / 100f) }
        assertTrue(samples.all { it in 0f..1f })
        assertEquals(1f, samples.max(), 0.06f)
        assertEquals(0f, heartbeat(0.7f), 0f)
    }
}
