package io.openshm.patcher.ui.guide

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.openshm.patcher.device.guide.PetPlacement

/** Which edge of the bubble the tail sticks out of, so it points at Tic. */
internal enum class BubbleTail { BottomRight, BottomLeft, TopRight, TopLeft, SideRight, SideLeft, Keyboard }

/** Top-left of the pet and of the bubble, in overlay pixels. */
internal data class GuideSpot(val pet: Offset, val bubble: Offset, val tail: BubbleTail)

/**
 * The overlay's usable area. [bottom] is the lowest y Tic may stand on (above the navigation bar),
 * [top] the highest (below the status bar and the control strip); [keyboardTop] is set while the
 * keyboard is up. [density] turns the dp constants of the design into pixels.
 */
internal class GuideArea(
    val width: Float,
    val height: Float,
    val density: Float,
    val top: Float,
    val bottom: Float,
    val keyboardTop: Float?,
) {
    fun dp(v: Float) = v * density
    val petWidth get() = dp(PET_WIDTH_DP)
    val petHeight get() = dp(PET_HEIGHT_DP)
}

internal const val PET_WIDTH_DP = 84f
internal const val PET_HEIGHT_DP = 109f

private enum class Corner { BottomRight, BottomLeft, TopRight, TopLeft }

private fun overlaps(a: Rect, b: Rect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

/** Where Tic and its bubble go for [placement], given the target the step is about (null when none). */
internal fun guideSpot(area: GuideArea, placement: PetPlacement, target: Rect?, bw: Float, bh: Float): GuideSpot =
    when {
        placement == PetPlacement.NearTarget && target != null -> {
            // Below the target first, then above; where neither leaves room for Tic and the bubble side by
            // side, a corner, so Tic never stands on its own words.
            val roomBelow = target.bottom + area.dp(6f) + area.petHeight <= area.bottom - area.dp(6f)
            val tries = if (roomBelow) listOf(true, false) else listOf(false)
            tries.firstNotNullOfOrNull { nearSpot(area, target, bw, bh, below = it) }
                ?: cornerSpot(area, chooseCorner(area, target, bw, bh), bw, bh)
        }
        placement == PetPlacement.AboveKeyboard && area.keyboardTop != null -> keyboardSpot(area, area.keyboardTop, bw, bh)
        placement == PetPlacement.Sleeping -> cornerSpot(area, Corner.BottomLeft, bw, bh)
        else -> cornerSpot(area, chooseCorner(area, target, bw, bh), bw, bh)
    }

private fun cornerSpot(a: GuideArea, c: Corner, bw: Float, bh: Float): GuideSpot {
    val right = c == Corner.BottomRight || c == Corner.TopRight
    val bottom = c == Corner.BottomRight || c == Corner.BottomLeft
    val px = if (right) a.width - a.petWidth - a.dp(10f) else a.dp(10f)
    val py = if (bottom) a.bottom - a.petHeight - a.dp(2f) else a.top
    val bx = if (right) maxOf(a.dp(8f), a.width - bw - a.dp(10f)) else a.dp(10f)
    val by = if (bottom) py - bh - a.dp(12f) else py + a.petHeight + a.dp(12f)
    val tail = when (c) {
        Corner.BottomRight -> BubbleTail.BottomRight
        Corner.BottomLeft -> BubbleTail.BottomLeft
        Corner.TopRight -> BubbleTail.TopRight
        Corner.TopLeft -> BubbleTail.TopLeft
    }
    return GuideSpot(Offset(px, py), Offset(bx, by), tail)
}

private fun chooseCorner(a: GuideArea, target: Rect?, bw: Float, bh: Float): Corner {
    if (target == null) return Corner.BottomRight
    val pad = a.dp(6f)
    val t = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
    for (c in Corner.entries) {
        val s = cornerSpot(a, c, bw, bh)
        val petBox = Rect(s.pet.x, s.pet.y, s.pet.x + a.petWidth, s.pet.y + a.petHeight)
        val bubbleBox = Rect(s.bubble.x, s.bubble.y, s.bubble.x + bw, s.bubble.y + bh)
        if (!overlaps(t, petBox) && !overlaps(t, bubbleBox)) return c
    }
    return Corner.BottomRight
}

/**
 * Tic walks next to what it is about to tap, [below] it or above, with the bubble beside Tic or past it.
 * Null when that leaves the bubble off the usable area or on top of Tic.
 */
private fun nearSpot(a: GuideArea, t: Rect, bw: Float, bh: Float, below: Boolean): GuideSpot? {
    val pw = a.petWidth
    val ph = a.petHeight
    val floor = a.bottom - a.dp(6f)
    val cx = t.center.x
    val py = if (below) t.bottom + a.dp(2f) else t.top - ph - a.dp(2f)
    if (py < a.top || py + ph > floor) return null
    val right = cx + a.dp(18f) + pw <= a.width - a.dp(8f)
    val px = if (right) cx + a.dp(18f) else maxOf(a.dp(8f), cx - a.dp(18f) - pw)
    val roomL = px - a.dp(8f)
    val roomR = a.width - (px + pw) - a.dp(8f)
    val need = bw + a.dp(12f)
    val spot = if (maxOf(roomL, roomR) >= need) {
        val rightTight = roomR < need
        val left = (roomL >= need && (!right || rightTight)) || rightTight
        val bx = if (left) px - bw - a.dp(12f) else px + pw + a.dp(12f)
        val raw = if (below) py + a.dp(8f) else py + ph - bh - a.dp(8f)
        val by = minOf(maxOf(a.top, raw), floor - bh)
        GuideSpot(Offset(px, py), Offset(bx, by), if (left) BubbleTail.SideRight else BubbleTail.SideLeft)
    } else {
        val bx = minOf(maxOf(a.dp(8f), px + pw / 2 - bw / 2), a.width - bw - a.dp(8f))
        val by = if (below) py + ph + a.dp(10f) else py - bh - a.dp(10f)
        val tail = if (below) (if (right) BubbleTail.TopRight else BubbleTail.TopLeft)
        else (if (right) BubbleTail.BottomRight else BubbleTail.BottomLeft)
        GuideSpot(Offset(px, py), Offset(bx, by), tail)
    }
    val pet = Rect(spot.pet.x, spot.pet.y, spot.pet.x + pw, spot.pet.y + ph)
    val bubble = Rect(spot.bubble.x, spot.bubble.y, spot.bubble.x + bw, spot.bubble.y + bh)
    if (bubble.top < a.top || bubble.bottom > floor || overlaps(pet, bubble)) return null
    return spot
}

/** Tic stands on the keyboard's right side, the bubble with the code boxes to its left. */
private fun keyboardSpot(a: GuideArea, keyboardTop: Float, bw: Float, bh: Float): GuideSpot {
    val px = a.width - a.petWidth - a.dp(8f)
    val py = keyboardTop - a.petHeight + a.dp(8f)
    return GuideSpot(
        Offset(px, py),
        Offset(maxOf(a.dp(8f), px - bw - a.dp(10f)), py + a.petHeight - bh - a.dp(18f)),
        BubbleTail.Keyboard,
    )
}


