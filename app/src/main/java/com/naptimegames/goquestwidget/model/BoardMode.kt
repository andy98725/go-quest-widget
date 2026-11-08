package com.naptimegames.goquestwidget.model

/**
 * Which games to spectate. GoQuest has two live lists, go9 and go13; 19x19 games
 * sometimes appear in the go13 list. [sizeFilter] narrows that list to one size, falling back
 * to the whole list when nothing matches.
 */
enum class BoardMode(val key: String, val gtype: String, val sizeFilter: Int?, val label: String) {
    NINE("9", "go9", null, "9×9"),
    THIRTEEN("13", "go13", 13, "13×13"),
    THIRTEEN_AND_NINETEEN("13+19", "go13", null, "13×13 & 19×19"),
    NINETEEN("19", "go13", 19, "19×19");

    /** Board to draw before any game state arrives. */
    val defaultBoardSize: Int get() = sizeFilter ?: if (this == NINE) 9 else 13

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}
