package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.widget.WidgetSizing
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetSizingTest {
    @Test
    fun typicalThreeByThreeWidget() {
        // ~3x3 cells on a 2.625x phone: portrait 260x300dp, landscape 340x220dp.
        val px = WidgetSizing.boardPx(minWidthDp = 260, maxWidthDp = 340, minHeightDp = 220, maxHeightDp = 300, density = 2.625f)
        // Portrait side = min(244, 240) = 240dp; landscape = min(324, 160) = 160dp -> 240dp = 630px -> 640.
        assertEquals(640, px)
    }

    @Test
    fun smallWidgetGetsSmallBitmap() {
        val px = WidgetSizing.boardPx(150, 180, 160, 190, density = 2f)
        // Portrait min(134, 130) = 130dp; landscape min(164, 100) = 100dp -> 260px -> bucket 288.
        assertEquals(288, px)
    }

    @Test
    fun clampedToRange() {
        assertEquals(WidgetSizing.MAX_PX, WidgetSizing.boardPx(600, 800, 600, 800, density = 3f))
        assertEquals(WidgetSizing.MIN_PX, WidgetSizing.boardPx(40, 40, 40, 40, density = 1f))
    }

    @Test
    fun unknownSizeUsesMax() {
        assertEquals(WidgetSizing.MAX_PX, WidgetSizing.boardPx(0, 0, 0, 0, density = 2f))
    }
}
