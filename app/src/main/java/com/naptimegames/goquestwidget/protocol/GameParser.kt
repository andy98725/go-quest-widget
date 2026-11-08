package com.naptimegames.goquestwidget.protocol

import com.naptimegames.goquestwidget.model.GameInfo
import com.naptimegames.goquestwidget.model.Player
import org.json.JSONObject

object GameParser {

    /** Parses a full game state (game / game_position / game_result messages). */
    fun parseGame(obj: JSONObject): GameInfo? {
        val id = obj.optString("id")
        val playersArr = obj.optJSONArray("players")
        if (id.isEmpty() || playersArr == null) return null
        val gtype = obj.optString("gtype", "")
        val position = obj.optJSONObject("position")
        val size = position?.optInt("size", 0)?.takeIf { it > 0 }
            ?: gtype.filter { it.isDigit() }.toIntOrNull()
            ?: 9

        val moves = ArrayList<String?>()
        val markers = ArrayList<String>()
        position?.optJSONArray("moves")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                // An entry can carry both a final move and the result, e.g. {"m":"B[]","s":"SCORE:4"}.
                if (m.has("m")) moves.add(m.optString("m"))
                if (m.has("s")) {
                    if (!m.has("m")) moves.add(null)
                    markers.add(m.optString("s"))
                }
            }
        }

        val players = (0 until playersArr.length()).mapNotNull { i ->
            val p = playersArr.optJSONObject(i) ?: return@mapNotNull null
            Player(
                id = p.optString("id"),
                name = p.optString("name").ifEmpty { p.optString("id") },
                dan = p.optIntOrNull("dan") ?: p.optIntOrNull("newD"),
                rating = p.optDoubleOrNull("rating") ?: p.optDoubleOrNull("newR"),
            )
        }

        val winner = (0 until playersArr.length()).firstOrNull { i ->
            val p = playersArr.optJSONObject(i)
            val old = p?.optDoubleOrNull("oldR")
            val new = p?.optDoubleOrNull("newR")
            old != null && new != null && new > old
        }

        val rem = obj.optJSONArray("remTime")?.let { a -> (0 until a.length()).map { a.optLong(it) } }

        return GameInfo(
            id = id,
            gtype = gtype,
            boardSize = size,
            players = players,
            moves = moves,
            statusMarkers = markers,
            finished = obj.optBoolean("finished", false),
            remainingMs = rem,
            winnerIndex = winner,
        )
    }

    /** One row of the live list, already in the server's order (strongest game first). */
    data class LiveGame(val id: String, val moveCount: Int, val black: Player, val white: Player)

    /**
     * Parses the live list's "games" array. Each entry is a '/'-separated row:
     * `id/moves/?/?/?/<black: name/?/dan/rating/?/avatar>/<white: same 6 fields>`.
     */
    fun parseLiveList(body: JSONObject): List<LiveGame> {
        val arr = body.optJSONArray("games") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> parseLiveRow(arr.optString(i)) }
    }

    fun parseLiveRow(row: String): LiveGame? {
        val f = row.split('/')
        if (f.size < 15 || f[0].isEmpty()) return null
        fun player(at: Int) = Player(
            id = f[at].lowercase(),
            name = f[at],
            dan = f[at + 2].toIntOrNull(),
            rating = f[at + 3].toDoubleOrNull(),
        )
        return LiveGame(f[0], f[1].toIntOrNull() ?: 0, player(5), player(11))
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) runCatching { getInt(key) }.getOrNull() else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) runCatching { getDouble(key) }.getOrNull() else null
}
