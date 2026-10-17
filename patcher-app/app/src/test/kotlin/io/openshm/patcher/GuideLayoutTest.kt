package io.openshm.patcher

import androidx.compose.ui.geometry.Rect
import io.openshm.patcher.device.guide.PetPlacement
import io.openshm.patcher.ui.guide.BubbleTail
import io.openshm.patcher.ui.guide.GuideArea
import io.openshm.patcher.ui.guide.GuideSpot
import io.openshm.patcher.ui.guide.guideSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where Tic and its bubble stand, on a 400x800 area at density 1 (1 px = 1 dp). */
class GuideLayoutTest {
    private val area = GuideArea(width = 400f, height = 800f, density = 1f, top = 34f, bottom = 774f, keyboardTop = null)
    private val bw = 236f
    private val bh = 90f

    private fun pet(s: GuideSpot) = Rect(s.pet.x, s.pet.y, s.pet.x + area.petWidth, s.pet.y + area.petHeight)
    private fun bubble(s: GuideSpot) = Rect(s.bubble.x, s.bubble.y, s.bubble.x + bw, s.bubble.y + bh)
    private fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    @Test fun cornerWithoutTargetIsBottomRight() {
        val s = guideSpot(area, PetPlacement.Corner, null, bw, bh)
        assertEquals(BubbleTail.BottomRight, s.tail)
        assertEquals(400f - 84f - 10f, s.pet.x, 0.01f)
        assertTrue("bubble sits above the pet", s.bubble.y + bh < s.pet.y)
    }

    @Test fun cornerMovesAwayFromATargetInTheBottomRight() {
        val target = Rect(0f, 640f, 400f, 720f)
        val s = guideSpot(area, PetPlacement.Corner, target, bw, bh)
        assertFalse(overlaps(target, pet(s)))
        assertFalse(overlaps(target, bubble(s)))
    }

    @Test fun nearTargetStandsBelowTheTargetBesideTheBubble() {
        val target = Rect(0f, 200f, 400f, 270f)
        val s = guideSpot(area, PetPlacement.NearTarget, target, bw, bh)
        assertTrue("pet below the target", s.pet.y >= target.bottom)
        assertFalse(overlaps(pet(s), target))
    }

    @Test fun nearTargetGoesAboveWhenThereIsNoRoomBelow() {
        val target = Rect(0f, 700f, 400f, 770f)
        val s = guideSpot(area, PetPlacement.NearTarget, target, bw, bh)
        assertTrue("pet above the target", s.pet.y + area.petHeight <= target.top)
    }

    @Test fun nearTargetNeverPutsTicOnItsBubble() {
        // The pairing row low on the screen: below it there is room for Tic but not for the bubble under it.
        for (top in listOf(150f, 400f, 560f, 600f, 640f, 680f, 700f)) {
            val target = Rect(0f, top, 400f, top + 70f)
            val s = guideSpot(area, PetPlacement.NearTarget, target, bw, bh)
            assertFalse("top=$top", overlaps(pet(s), bubble(s)))
            assertTrue("top=$top", bubble(s).top >= area.top && bubble(s).bottom <= area.bottom)
        }
    }

    @Test fun sleepingStandsBottomLeftAboveTheFloor() {
        val s = guideSpot(area, PetPlacement.Sleeping, null, bw, bh)
        assertEquals(10f, s.pet.x, 0.01f)
        assertTrue(s.pet.y + area.petHeight <= area.bottom)
    }

    @Test fun aboveKeyboardStandsOnTheKeyboardWithTheBubbleToItsLeft() {
        val withKeyboard = GuideArea(400f, 800f, 1f, 34f, 774f, keyboardTop = 500f)
        val s = guideSpot(withKeyboard, PetPlacement.AboveKeyboard, null, bw, bh)
        assertEquals(BubbleTail.Keyboard, s.tail)
        assertTrue(s.pet.y + withKeyboard.petHeight <= 500f + 8f + 0.01f)
        assertTrue(s.bubble.x + bw <= s.pet.x)
    }
}
