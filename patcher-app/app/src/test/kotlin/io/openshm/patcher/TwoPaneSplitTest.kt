package io.openshm.patcher

import io.openshm.patcher.ui.screens.install.twoPaneSplit
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoPaneSplitTest {

    @Test
    fun singleOrNoItemsStayInTheFirstPane() {
        assertEquals(0, twoPaneSplit(emptyList()))
        assertEquals(1, twoPaneSplit(listOf(9)))
    }

    @Test
    fun rowsAreBalancedBetweenThePanes() {
        assertEquals(1, twoPaneSplit(listOf(5, 5)))
        assertEquals(1, twoPaneSplit(listOf(7, 2, 3)))
        assertEquals(2, twoPaneSplit(listOf(2, 3, 6)))
    }

    @Test
    fun bothPanesGetAtLeastOneItem() {
        assertEquals(1, twoPaneSplit(listOf(1, 20)))
        assertEquals(1, twoPaneSplit(listOf(20, 1)))
    }
}
