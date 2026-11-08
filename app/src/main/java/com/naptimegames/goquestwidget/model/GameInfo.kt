package com.naptimegames.goquestwidget.model

data class Player(
    val id: String,
    val name: String,
    /** GoQuest rank: positive = dan, negative = kyu. */
    val dan: Int?,
    val rating: Double?,
) {
    val rankLabel: String?
        get() = when {
            dan == null -> null
            dan > 0 -> "${dan}d"
            dan < 0 -> "${-dan}k"
            else -> null
        }
}

data class GameInfo(
    val id: String,
    val gtype: String,
    val boardSize: Int,
    /** players[0] is Black, players[1] is White. */
    val players: List<Player>,
    /** Raw SGF moves in order; null entries are non-move markers such as resignations. */
    val moves: List<String?>,
    /** Status markers found in the move list, e.g. "LOSE:RESIGN". */
    val statusMarkers: List<String>,
    val finished: Boolean,
    val remainingMs: List<Long>?,
    /** 0 = Black won, 1 = White won, null = unknown (from rating changes in the result message). */
    val winnerIndex: Int? = null,
) {
    val black: Player? get() = players.getOrNull(0)
    val white: Player? get() = players.getOrNull(1)

    val moveCount: Int get() = moves.count { it != null }

    /** Index into [players] of the side to move: whoever didn't play the last move (Black first). */
    val toMove: Int
        get() = if (moves.lastOrNull { it != null }?.startsWith("B") == true) 1 else 0

    /** Why the game ended, from the last status marker: "Resign", "Time", "4 pts", ... */
    val resultReason: String?
        get() {
            val marker = statusMarkers.lastOrNull() ?: return null
            val kind = marker.substringBefore(':').uppercase()
            val detail = marker.substringAfter(':', "")
            return when {
                // The score is signed from Black's side ("SCORE:-10" = White by 10); show the margin.
                kind == "SCORE" && detail.isNotEmpty() -> "${detail.trimStart('-', '+')} pts"
                detail.isEmpty() -> kind.lowercase().replaceFirstChar { it.uppercase() }.takeIf { it.isNotEmpty() }
                else -> detail.lowercase().replaceFirstChar { it.uppercase() }
            }
        }

    /** Short human-readable result, e.g. "B+Resign", or null while the game is running. */
    /** 0 = Black, 1 = White: from rating changes, else from the sign of a score result. */
    val winner: Int?
        get() {
            winnerIndex?.let { return it }
            val marker = statusMarkers.lastOrNull() ?: return null
            if (!marker.uppercase().startsWith("SCORE:")) return null
            val score = marker.substringAfter(':').toDoubleOrNull() ?: return null
            return when {
                score > 0 -> 0
                score < 0 -> 1
                else -> null
            }
        }

    val resultLabel: String?
        get() {
            if (!isOver) return null
            val reason = resultReason
            val winner = when (winner) {
                0 -> "B"
                1 -> "W"
                else -> null
            }
            return when {
                winner != null && reason != null -> "$winner+$reason"
                winner != null -> "$winner wins"
                reason != null -> reason
                else -> "Game over"
            }
        }

    /** A game is over once the server marks it finished or a status marker (resign, timeout, ...) appears. */
    val isOver: Boolean get() = finished || statusMarkers.isNotEmpty()
}
