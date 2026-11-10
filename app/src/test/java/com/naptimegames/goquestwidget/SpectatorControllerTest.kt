package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.SpectatorState.Status
import com.naptimegames.goquestwidget.protocol.Protocol
import com.naptimegames.goquestwidget.protocol.Protocol.Api
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.naptimegames.goquestwidget.model.BoardMode
import org.junit.Test

class SpectatorControllerTest {
    private val sent = mutableListOf<Pair<Api, JSONObject>>()
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

    private fun controller(
        mode: BoardMode = BoardMode.NINE,
        topN: Int = 1,
        delayMs: Long = SpectatorController.DEFAULT_SWITCH_DELAY_MS,
    ) = SpectatorController(
        mode = mode,
        topN = topN,
        switchDelayMs = delayMs,
        send = { a, b -> sent += a to b },
        scheduler = { d, t ->
            val entry = d to t
            tasks += entry
            SpectatorController.Cancellable { tasks.remove(entry) }
        },
        onState = {},
    )

    /** Runs scheduled tasks whose delay is exactly [delay]. */
    private fun fire(delay: Long) {
        val due = tasks.filter { it.first == delay }
        tasks.removeAll(due)
        due.forEach { it.second() }
    }

    /** A live-list row; "id:moves" sets the move count (default 10), "id:moves:go19" marks a 19x19 game. */
    private fun row(spec: String): String {
        val parts = spec.split(':')
        val id = parts[0]
        val moves = parts.getOrElse(1) { "10" }
        val type = parts.getOrElse(2) { "" }
        return "$id/$moves/$type///b_$id//3/2000/0//w_$id//2/1900/0/"
    }
    private fun live(gtype: String, vararg ids: String) =
        JSONObject().put("gtype", gtype).put("page", 0).put("games", JSONArray().apply { ids.forEach { put(row(it)) } })

    private fun state(id: String, moves: List<String>, marker: String? = null, size: Int = 9) = JSONObject()
        .put("id", id).put("gtype", "go$size")
        .put("players", JSONArray().put(JSONObject().put("id", "b").put("name", "b")).put(JSONObject().put("id", "w").put("name", "w")))
        .put("position", JSONObject().put("size", size).put("moves", JSONArray().apply {
            moves.forEach { put(JSONObject().put("m", it)) }
            marker?.let { put(JSONObject().put("s", it)) }
        }))

    @Test
    fun watchesTopOfListAndSwitchesFiveSecondsAfterItEnds() {
        val c = controller()
        c.onReady()
        assertEquals(Api.LIVE, sent.last().first)
        assertEquals("go9", sent.last().second.getString("gtype"))

        // A list for the other board size is ignored.
        c.onMessage(Api.LIVE, live("go13", "x13"))
        assertEquals(Status.SEARCHING, c.state.status)

        c.onMessage(Api.LIVE, live("go9", "top", "second"))
        assertEquals(listOf(Api.LIVE_STOP, Api.GAME), sent.takeLast(2).map { it.first })
        assertEquals("top", sent.last().second.getString("id"))
        assertEquals(Status.WATCHING, c.state.status)
        assertEquals("b_top", c.state.game?.black?.name)

        c.onMessage(Api.GAME_POSITION, state("top", listOf("B[ee]", "W[ec]")))
        assertEquals(2, c.state.game?.moveCount)
        c.onMessage(Api.GAME_POSITION, state("other", listOf("B[aa]")))
        assertEquals("top", c.state.game?.id)

        c.onMessage(Api.GAME_POSITION, state("top", listOf("B[ee]", "W[ec]"), "LOSE:RESIGN"))
        assertEquals(Status.FINISHED, c.state.status)
        assertEquals(Api.GAME_STOP, sent.last().first)

        val before = sent.size
        fire(SpectatorController.DEFAULT_SWITCH_DELAY_MS - 1)
        assertEquals(before, sent.size)
        fire(SpectatorController.DEFAULT_SWITCH_DELAY_MS)
        assertEquals(Api.LIVE, sent.last().first)

        // The finished game is skipped even if still listed.
        c.onMessage(Api.LIVE, live("go9", "top", "second"))
        assertEquals("second", sent.last().second.getString("id"))
    }

    @Test
    fun postGameDelayIsConfigurable() {
        val c = controller(delayMs = 30_000)
        c.onReady()
        c.onMessage(Api.LIVE, live("go9", "g1", "g2"))
        c.onMessage(Api.GAME_POSITION, state("g1", listOf("B[ee]"), "LOSE:RESIGN"))
        assertEquals(Status.FINISHED, c.state.status)
        fire(SpectatorController.DEFAULT_SWITCH_DELAY_MS)
        assertEquals(Status.FINISHED, c.state.status) // still lingering
        fire(30_000)
        assertEquals(Status.SEARCHING, c.state.status)

        // A changed delay applies to the next finished game.
        c.switchDelayMs = 0
        c.onMessage(Api.LIVE, live("go9", "g2"))
        c.onMessage(Api.GAME_POSITION, state("g2", listOf("B[ee]"), "LOSE:TIME"))
        fire(0)
        assertEquals(Status.SEARCHING, c.state.status)
    }

    @Test
    fun retriesListWhileSearching() {
        val c = controller()
        c.onReady()
        fire(SpectatorController.RETRY_LIST_MS)
        assertEquals(2, sent.count { it.first == Api.LIVE })
    }

    @Test
    fun reconnectResumesSameGame() {
        val c = controller()
        c.onReady()
        c.onMessage(Api.LIVE, live("go9", "g1"))
        c.onDisconnected("lost")
        assertEquals(Status.OFFLINE, c.state.status)
        sent.clear()
        c.onReady()
        assertEquals(Api.GAME, sent.single().first)
        assertEquals("g1", sent.single().second.getString("id"))
    }

    private fun lastWatched() = sent.last { it.first == Api.GAME }.second.getString("id")

    @Test
    fun topNWatchesFewestMoves() {
        val c = controller(topN = 3)
        c.onReady()
        // Server order is strongest first; among the top 3, "fresh" has the fewest moves.
        c.onMessage(Api.LIVE, live("go9", "a:40", "fresh:5", "b:20", "fresher:1"))
        assertEquals("fresh", lastWatched())
    }

    @Test
    fun topNTiesKeepServerOrder() {
        val c = controller(topN = 2)
        c.onReady()
        c.onMessage(Api.LIVE, live("go9", "first:7", "second:7"))
        assertEquals("first", lastWatched())
    }

    @Test
    fun topNBeyondListUsesWholeList() {
        val c = controller(topN = 10)
        c.onReady()
        c.onMessage(Api.LIVE, live("go9", "a:40", "b:3"))
        assertEquals("b", lastWatched())
    }

    @Test
    fun nineteenFilterUsesRowType() {
        val c = controller(BoardMode.NINETEEN, topN = 1)
        c.onReady()
        c.onMessage(Api.LIVE, live("go13", "t1:10", "big:107:go19", "t2:5"))
        // Picked straight from the list: no game opened just to check its size.
        assertEquals(listOf("big"), sent.filter { it.first == Api.GAME }.map { it.second.getString("id") })
        assertEquals(19, c.state.game?.boardSize)
        assertEquals("go19", c.state.game?.gtype)
    }

    @Test
    fun thirteenFilterSkipsNineteen() {
        val c = controller(BoardMode.THIRTEEN, topN = 2)
        c.onReady()
        // Top 2 13x13 games are t1 and t2 (big is 19x19); t2 has fewer moves.
        c.onMessage(Api.LIVE, live("go13", "t1:30", "big:2:go19", "t2:12", "t3:1"))
        assertEquals("t2", lastWatched())
        assertEquals(13, c.state.game?.boardSize)
    }

    @Test
    fun sizeFilterFallsBackToUnfilteredList() {
        val c = controller(BoardMode.NINETEEN, topN = 2)
        c.onReady()
        c.onMessage(Api.LIVE, live("go13", "a:30", "b:12", "c:2"))
        // No 19x19 at all: pick from the unfiltered top 2 by fewest moves ("c" isn't in the top 2).
        assertEquals(Status.WATCHING, c.state.status)
        assertEquals("b", c.state.game?.id)
    }

    @Test
    fun mixedModeTakesAnySize() {
        val c = controller(BoardMode.THIRTEEN_AND_NINETEEN, topN = 3)
        c.onReady()
        c.onMessage(Api.LIVE, live("go13", "a:9", "b:4:go19", "c:6"))
        assertEquals(1, sent.count { it.first == Api.GAME })
        assertEquals("b", lastWatched())
    }

    /** Replays a server transcript: the widget should follow the top 9x9 game to its end and ignore others. */
    @Test
    fun replayOfServerTranscript() {
        val transcript = Transcript.lines()
        val api = Protocol.ApiTable.fromStart(transcript.first().body)
        val c = controller(BoardMode.NINE)
        c.onReady()
        val seen = mutableListOf<Status>()
        for (msg in transcript.drop(1)) {
            api.lookup(msg.name)?.let { c.onMessage(it, msg.body) }
            seen += c.state.status
        }
        val watched = sent.filter { it.first == Api.GAME }.map { it.second.getString("id") }
        assertEquals(listOf("gq9topgame01"), watched)
        assertEquals(Status.FINISHED, c.state.status)
        assertEquals("W+Resign", c.state.game?.resultLabel)
        assertTrue(c.state.game!!.moveCount >= 50)
        fire(SpectatorController.DEFAULT_SWITCH_DELAY_MS)
        assertEquals(Api.LIVE, sent.last().first)
    }
}
