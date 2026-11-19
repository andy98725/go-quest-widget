package com.naptimegames.goquestwidget.model

/** One widget's settings. */
data class WidgetSettings(
    val mode: BoardMode,
    val topN: Int,
    val postGameDelaySeconds: Int,
    val backgroundOpacity: Int,
    val boardOpacity: Int,
    val includeBots: Boolean = true,
    /** Pause while the phone is on mobile data. */
    val wifiOnly: Boolean = false,
) {
    /** Widgets with the same key watch the same game over one connection; opacity only affects drawing. */
    val groupKey: GroupKey get() = GroupKey(mode, topN, postGameDelaySeconds, includeBots, wifiOnly)

    data class GroupKey(
        val mode: BoardMode,
        val topN: Int,
        val postGameDelaySeconds: Int,
        val includeBots: Boolean,
        val wifiOnly: Boolean,
    )
}
