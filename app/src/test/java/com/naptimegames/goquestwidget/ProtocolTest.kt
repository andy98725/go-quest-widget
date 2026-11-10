package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.protocol.GameParser
import com.naptimegames.goquestwidget.protocol.Protocol
import com.naptimegames.goquestwidget.protocol.Protocol.Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses a synthetic server transcript in GoQuest's wire format (made-up players and games). */
class ProtocolTest {
    private val transcript = Transcript.lines()

    @Test
    fun parsesLines() {
        val m = Protocol.parseLine("""d40a7297 {"id":"abc","num":3}""")!!
        assertEquals("d40a7297", m.name)
        assertEquals(3, m.body.getInt("num"))
        assertNull(Protocol.parseLine(""))
        assertNull(Protocol.parseLine("name {not json"))
        assertEquals("start {\"gtype\":\"go9\"}\n", Protocol.line("start", JSONObject().put("gtype", "go9")))
    }

    @Test
    fun apiTableFromHandshakeMatchesKnownIds() {
        val start = transcript.first()
        assertEquals(Protocol.START, start.name)
        val table = Protocol.ApiTable.fromStart(start.body)
        for (api in Api.entries) assertEquals(api.name, api.fallbackId, table.id(api))
        assertEquals(Api.GAME_POSITION, table.lookup("8d7a2124"))
        assertNull(table.lookup("ffffffff"))
    }

    @Test
    fun apiTableFollowsRenamedIds() {
        val table = Protocol.ApiTable.fromStart(JSONObject().put("api", JSONObject().put("Q40A", "12345678")))
        assertEquals("12345678", table.id(Api.LIVE))
        assertEquals(Api.LIVE, table.lookup("12345678"))
        assertEquals(Api.GAME.fallbackId, table.id(Api.GAME))
    }

    @Test
    fun parsesLiveList() {
        val list = transcript.first { it.name == Api.LIVE.fallbackId && it.body.getString("gtype") == "go9" }
        val games = GameParser.parseLiveList(list.body)
        assertEquals(25, games.size)
        val top = games.first()
        assertEquals("gq9topgame01", top.id)
        assertEquals(29, top.moveCount)
        assertEquals(3, top.black.dan)
        assertEquals(2072.0, top.black.rating!!, 0.0)
        assertEquals(4, top.white.dan)
        assertEquals(2140.0, top.white.rating!!, 0.0)
    }

    @Test
    fun liveRowWithKyuAndBadRows() {
        val row = GameParser.parseLiveRow("5t4yrk1p1mdw/53////Alice//4/2090/0//bob//-3/1821/0/")!!
        assertEquals("Alice", row.black.name)
        assertEquals("3k", row.white.rankLabel)
        assertNull(GameParser.parseLiveRow("garbage"))
    }

    @Test
    fun liveRowGameType() {
        // Real row shape: 19x19 games in the go13 list carry "go19" in the third field.
        val big = GameParser.parseLiveRow("tub610vwwqmq/107/go19///p1//0/1663/0//p2//6/2391/0/s_n06", "go13")!!
        assertEquals("go19", big.gtype)
        assertEquals(19, big.boardSize)
        val small = GameParser.parseLiveRow("k9f2pnyzsqq8/41////p3//5/2183/0//p4//2/1864/0/", "go13")!!
        assertEquals("go13", small.gtype)
        assertEquals(13, small.boardSize)
    }

    @Test
    fun parsesGameAndResult() {
        val state = transcript.first { it.name == Api.GAME.fallbackId && it.body.getString("id") == "gq9topgame01" }
        val game = GameParser.parseGame(state.body)!!
        assertEquals(9, game.boardSize)
        assertEquals(2, game.players.size)
        assertFalse(game.isOver)
        assertTrue(game.moveCount > 30)

        val result = transcript.first { it.name == Api.GAME_RESULT.fallbackId }
        val final = GameParser.parseGame(result.body)!!
        assertTrue(final.isOver)
        assertEquals(1, final.winnerIndex)
        assertEquals("W+Resign", final.resultLabel)
    }

    @Test
    fun sideToMoveFollowsLastMove() {
        fun game(moves: String) = GameParser.parseGame(JSONObject(
            """{"id":"x","gtype":"go9","players":[],"position":{"moves":[$moves]}}"""))!!
        assertEquals(0, game("").toMove)
        assertEquals(1, game("""{"m":"B[ee]"}""").toMove)
        assertEquals(0, game("""{"m":"B[ee]"},{"m":"W[ec]"}""").toMove)
    }

    @Test
    fun startErrorFallsBackToKnownIds() {
        // Seen live: the server rejects the widget's vendor_id but keeps serving spectators.
        val table = Protocol.ApiTable.fromStart(JSONObject().put("error", "Unknown vendor_id"))
        assertEquals("b878053d", table.id(Api.LIVE))
        assertEquals(Api.GAME_RESULT, table.lookup("1f566a1a"))
    }

    @Test
    fun scoreMarkerOnFinalPass() {
        // Seen live: the last entry holds both the final pass and the result.
        val result = GameParser.parseGame(JSONObject("""{"id":"g","gtype":"go9",
            "players":[{"id":"b","name":"b","oldR":2066.5,"newR":2074.8},{"id":"w","name":"w","oldR":2201.3,"newR":2193.2}],
            "position":{"moves":[{"t":1,"m":"B[gd]"},{"t":2293,"m":"W[]"},{"t":1056,"m":"B[]","s":"SCORE:4"}],"size":9},
            "finished":true}"""))!!
        assertEquals(listOf("SCORE:4"), result.statusMarkers)
        assertEquals(3, result.moveCount)
        assertEquals("4 pts", result.resultReason)
        assertEquals("B+4 pts", result.resultLabel)

        // Even without "finished", the marker alone ends the game.
        val position = GameParser.parseGame(JSONObject("""{"id":"g","players":[],
            "position":{"moves":[{"m":"W[]"},{"m":"B[]","s":"SCORE:4"}]}}"""))!!
        assertTrue(position.isOver)
    }

    @Test
    fun negativeScoreIsAWhiteWin() {
        // Without rating data, the score's sign decides the winner.
        val g = GameParser.parseGame(JSONObject("""{"id":"g","players":[],
            "position":{"moves":[{"m":"B[]"},{"m":"W[]","s":"SCORE:-10"}]},"finished":true}"""))!!
        assertEquals(1, g.winner)
        assertEquals("W+10 pts", g.resultLabel)
        // Rating changes, when present, take precedence.
        assertEquals(0, GameParser.parseGame(JSONObject("""{"id":"g","players":[
            {"id":"b","oldR":1,"newR":2},{"id":"w","oldR":2,"newR":1}],
            "position":{"moves":[{"s":"SCORE:-10"}]}}"""))!!.winner)
    }

    @Test
    fun drawsHaveNoWinner() {
        // The lower-rated player gains rating in a draw; that must not make them the winner.
        fun result(marker: String) = GameParser.parseGame(JSONObject("""{"id":"g","players":[
            {"id":"b","oldR":2000,"newR":1998},{"id":"w","oldR":1900,"newR":1902}],
            "position":{"moves":[{"m":"B[]"},{"m":"W[]","s":"$marker"}]},"finished":true}"""))!!
        for (marker in listOf("DRAW", "DRAW:SCORE", "SCORE:0", "SCORE:0.0")) {
            val g = result(marker)
            assertTrue(marker, g.isDraw)
            assertNull(marker, g.winner)
            assertEquals(marker, "Draw", g.resultLabel)
        }
        assertFalse(result("SCORE:0.5").isDraw)
        assertEquals(1, result("SCORE:-0.5").winner)
    }

    @Test
    fun resultReasons() {
        fun reason(marker: String) = GameParser.parseGame(JSONObject(
            """{"id":"g","players":[],"position":{"moves":[{"s":"$marker"}]}}"""))!!.resultReason
        assertEquals("Resign", reason("LOSE:RESIGN"))
        assertEquals("Disconnect", reason("LOSE:DISCONNECT"))
        assertEquals("Timeout", reason("TIME"))
        assertEquals("Timeout", reason("LOSE:TIMEUP"))
        assertEquals("12 pts", reason("SCORE:12"))
        assertEquals("10 pts", reason("SCORE:-10"))
        assertEquals("6.5 pts", reason("SCORE:-6.5"))
    }

    @Test
    fun boardSizeFallsBackToGtype() {
        val g = GameParser.parseGame(JSONObject("""{"id":"x","gtype":"go13","players":[],"position":{"moves":[]}}"""))!!
        assertEquals(13, g.boardSize)
    }
}

object Transcript {
    fun lines(): List<Protocol.Message> =
        javaClass.classLoader!!.getResourceAsStream("server_transcript.txt")!!.bufferedReader()
            .readLines().mapNotNull(Protocol::parseLine)
}
