package com.naptimegames.goquestwidget.model

/** One widget's settings. */
data class WidgetSettings(
    val mode: BoardMode,
    val topN: Int,
    val postGameDelaySeconds: Int,
    val backgroundOpacity: Int,
    val boardOpacity: Int,
) {
    /** Widgets with the same key watch the same game; opacity only affects drawing. */
    val groupKey: GroupKey get() = GroupKey(mode, topN, postGameDelaySeconds)

    data class GroupKey(val mode: BoardMode, val topN: Int, val postGameDelaySeconds: Int)
}
