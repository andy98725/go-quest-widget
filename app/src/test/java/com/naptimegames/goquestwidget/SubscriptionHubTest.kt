package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.SpectatorState.Status
import com.naptimegames.goquestwidget.model.BoardMode
import com.naptimegames.goquestwidget.protocol.Protocol.Api
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionHubTest {
    private val wire = mutableListOf<Pair<Api, String>>()
    private val hub = SubscriptionHub { api, body -> wire += api to (body.optString("id").ifEmpty { body.optString("gtype") }) }

    private fun game(id: String) = JSONObject().put("id", id)
    private fun live(gtype: String) = JSONObject().put("gtype", gtype).put("page", 0)

    @Test
    fun gameStopOnlyWhenLastOwnerLeaves() {
        val a = hub.sender("a"); val b = hub.sender("b")
        a(Api.GAME, game("x"))
        b(Api.GAME, game("x"))
        assertEquals(2, wire.count { it.first == Api.GAME }) // newcomer still gets the full state
        a(Api.GAME_STOP, game("x"))
        assertEquals(0, wire.count { it.first == Api.GAME_STOP })
        b(Api.GAME_STOP, game("x"))
        assertEquals(listOf(Api.GAME_STOP to "x"), wire.filter { it.first == Api.GAME_STOP })
    }

    @Test
    fun liveStopPerBoardType() {
        val a = hub.sender("a"); val b = hub.sender("b")
        a(Api.LIVE, live("go9"))
        b(Api.LIVE, live("go13"))
        a(Api.LIVE_STOP, live("go9"))
        assertEquals(listOf(Api.LIVE_STOP to "go9"), wire.filter { it.first == Api.LIVE_STOP })
    }

    @Test
    fun repeatedStopsAndStrangersAreIgnored() {
        val a = hub.sender("a"); val b = hub.sender("b")
        a(Api.GAME, game("x"))
        b(Api.GAME_STOP, game("x")) // b never watched it
        a(Api.GAME_STOP, game("x"))
        a(Api.GAME_STOP, game("x")) // already released
        assertEquals(1, wire.count { it.first == Api.GAME_STOP })
    }

    @Test
    fun resetForgetsOwners() {
        val a = hub.sender("a")
        a(Api.GAME, game("x"))
        hub.reset()
        a(Api.GAME_STOP, game("x"))
        assertEquals(0, wire.count { it.first == Api.GAME_STOP })
    }

    /** Two widgets with different settings on one connection: one checking a game's size must not stop the other's game. */
    @Test
    fun twoControllersShareAGame() {
        val noTimers = SpectatorController.Scheduler { _, _ -> SpectatorController.Cancellable {} }
        lateinit var nine: SpectatorController
        lateinit var thirteen: SpectatorController
        nine = SpectatorController(BoardMode.THIRTEEN_AND_NINETEEN, 1, send = { m, b -> hub.sender(nine)(m, b) }, scheduler = noTimers, onState = {})
        thirteen = SpectatorController(BoardMode.THIRTEEN, 1, send = { m, b -> hub.sender(thirteen)(m, b) }, scheduler = noTimers, onState = {})
        val list = JSONObject().put("gtype", "go13").put("page", 0)
            .put("games", JSONArray().put("g1/10////b//3/2000/0//w//2/1900/0/"))
        val state = JSONObject().put("id", "g1").put("gtype", "go13")
            .put("players", JSONArray()).put("position", JSONObject().put("size", 13).put("moves", JSONArray()))

        nine.onReady(); thirteen.onReady()
        for (c in listOf(nine, thirteen)) c.onMessage(Api.LIVE, list)
        // "nine" (unfiltered) watches g1 at once; "thirteen" opens g1 to check its size.
        for (c in listOf(nine, thirteen)) c.onMessage(Api.GAME, state)
        assertEquals(Status.WATCHING, nine.state.status)
        assertEquals(Status.WATCHING, thirteen.state.status)
        // The size check's game_stop was absorbed: both still watch g1.
        assertEquals(0, wire.count { it.first == Api.GAME_STOP })
        nine.stop()
        assertEquals(0, wire.count { it.first == Api.GAME_STOP })
        thirteen.stop()
        assertEquals(listOf(Api.GAME_STOP to "g1"), wire.filter { it.first == Api.GAME_STOP })
    }
}
