package com.naptimegames.goquestwidget.widget

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Works out how many pixels the board needs for a widget's reported size. Pure, for testing. */
object WidgetSizing {
    /** Widget padding (8dp each side). */
    private const val HORIZONTAL_CHROME_DP = 16
    /** Padding, two player rows and the board's margins. */
    private const val VERTICAL_CHROME_DP = 60

    const val MIN_PX = 160
    const val MAX_PX = 640
    /** Sizes are rounded up to a multiple of this so similar widgets share one bitmap. */
    private const val BUCKET_PX = 32

    /**
     * Launchers report a min/max width and height in dp: portrait is min width × max height,
     * landscape is max width × min height. Draw for the larger of the two so the board is
     * sharp in both orientations.
     */
    fun boardPx(minWidthDp: Int, maxWidthDp: Int, minHeightDp: Int, maxHeightDp: Int, density: Float): Int {
        if (minWidthDp <= 0 && maxWidthDp <= 0) return MAX_PX // launcher didn't say
        fun side(w: Int, h: Int) = min(w - HORIZONTAL_CHROME_DP, h - VERTICAL_CHROME_DP)
        val dp = max(side(minWidthDp, maxHeightDp), side(maxWidthDp, minHeightDp))
        val px = (dp * density).roundToInt()
        val bucketed = (px + BUCKET_PX - 1) / BUCKET_PX * BUCKET_PX
        return bucketed.coerceIn(MIN_PX, MAX_PX)
    }
}
