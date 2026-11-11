package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.model.BoardMode
import com.naptimegames.goquestwidget.model.GameInfo
import com.naptimegames.goquestwidget.protocol.GameParser
import com.naptimegames.goquestwidget.protocol.GameParser.LiveGame
import com.naptimegames.goquestwidget.protocol.Protocol.Api
import org.json.JSONObject

/** What the widget shows. */
data class SpectatorState(
    val status: Status,
    val mode: BoardMode,
    val game: GameInfo? = null,
    val message: String? = null,
    /** When [game]'s clock snapshot arrived (SystemClock.elapsedRealtime); set by the service. */
    val clockAtMs: Long = 0,
) {
    enum class Status { STOPPED, CONNECTING, SEARCHING, WATCHING, FINISHED, OFFLINE }
}

/**
 * Spectating logic, kept free of Android so it can be unit-tested.
 *
 * Picking a game: take the live list (the server sorts it strongest first), keep games of the
 * selected size (list rows say "go19" for 19x19 games in the go13 list), and from the first
 * [topN] of those watch the one with the fewest moves, so a fresh game is preferred over one
 * about to end. If no game matches the size filter, use the unfiltered list instead.
 * [switchDelayMs] after the watched game ends, pick again.
 *
 * The server sends moves for only one watched game per connection, so each controller needs
 * a connection of its own.
 */
class SpectatorController(
    val mode: BoardMode,
    topN: Int,
    switchDelayMs: Long = DEFAULT_SWITCH_DELAY_MS,
    private val send: (api: Api, body: JSONObject) -> Unit,
    private val scheduler: Scheduler,
    private val onState: (SpectatorState) -> Unit,
) {
    fun interface Cancellable { fun cancel() }
    fun interface Scheduler { fun schedule(delayMs: Long, task: () -> Unit): Cancellable }

    /** Applies from the next pick on. */
    var topN: Int = topN.coerceAtLeast(1)
        set(value) { field = value.coerceAtLeast(1) }

    /** How long a finished game stays on screen; applies from the next finished game. */
    var switchDelayMs: Long = switchDelayMs.coerceAtLeast(0)
        set(value) { field = value.coerceAtLeast(0) }

    val gtype: String get() = mode.gtype

    var state = SpectatorState(SpectatorState.Status.STOPPED, mode)
        private set

    private var connected = false
    private var current: GameInfo? = null
    private val recentlyFinished = ArrayDeque<String>()
    private var pending: Cancellable? = null
    private var watchdog: Cancellable? = null


    fun onConnecting() = publish(state.copy(status = SpectatorState.Status.CONNECTING, message = null))

    /** The server finished the `start` handshake. */
    fun onReady() {
        connected = true
        val game = current
        if (game != null && !game.isOver) watch(game) else search()
    }

    fun onDisconnected(reason: String) {
        connected = false
        cancelTimers()
        publish(state.copy(status = SpectatorState.Status.OFFLINE, message = reason))
    }

    fun stop() {
        if (connected) {
            current?.let { send(Api.GAME_STOP, JSONObject().put("id", it.id)) }
            if (state.status == SpectatorState.Status.SEARCHING) send(Api.LIVE_STOP, liveBody())
        }
        connected = false
        cancelTimers()
        current = null
        publish(SpectatorState(SpectatorState.Status.STOPPED, mode))
    }

    fun onMessage(api: Api, body: JSONObject) {
        when (api) {
            Api.LIVE -> onLiveList(body)
            Api.GAME, Api.GAME_POSITION -> GameParser.parseGame(body)?.let(::onGame)
            Api.GAME_RESULT -> GameParser.parseGame(body)?.let { onGame(it.copy(finished = true)) }
            else -> Unit
        }
    }

    private fun onGame(game: GameInfo) = onGameState(game)

    // ---- Picking a game ----

    private fun onLiveList(body: JSONObject) {
        if (state.status != SpectatorState.Status.SEARCHING) return
        if (body.optString("gtype", gtype) != gtype) return
        val rows = GameParser.parseLiveList(body).filter { it.id !in recentlyFinished }
        if (rows.isEmpty()) return
        pending?.cancel()
        send(Api.LIVE_STOP, liveBody())
        val filter = mode.sizeFilter
        val matching = if (filter == null) rows else rows.filter { it.boardSize == filter }
        // No game of the chosen size: use the whole list.
        val pool = matching.ifEmpty { rows }.take(topN)
        // Fewest moves = the earliest game; ties keep the server's (strength) order.
        val pick = pool.minByOrNull { it.moveCount } ?: return search()
        watch(preview(pick))
    }

    private fun preview(row: LiveGame) = GameInfo(
        id = row.id, gtype = row.gtype.ifEmpty { gtype }, boardSize = row.boardSize,
        players = listOf(row.black, row.white), moves = emptyList(), statusMarkers = emptyList(),
        finished = false, remainingMs = null,
    )

    // ---- Watching ----

    private fun onGameState(game: GameInfo) {
        val watching = current ?: return
        if (game.id != watching.id) return
        // The result message has no clock data; keep the last known clocks.
        val merged = game.copy(remainingMs = game.remainingMs ?: watching.remainingMs)
        current = merged
        if (merged.isOver) onFinished(merged) else {
            armWatchdog()
            publish(SpectatorState(SpectatorState.Status.WATCHING, mode, merged))
        }
    }

    private fun watch(game: GameInfo) {
        pending?.cancel()
        current = game
        send(Api.GAME, JSONObject().put("id", game.id))
        armWatchdog()
        publish(SpectatorState(SpectatorState.Status.WATCHING, mode, game))
    }

    private fun onFinished(game: GameInfo) {
        if (state.status == SpectatorState.Status.FINISHED && state.game?.id == game.id) {
            publish(state.copy(game = game)) // e.g. result arriving after a resign marker
            return
        }
        watchdog?.cancel()
        remember(game.id)
        send(Api.GAME_STOP, JSONObject().put("id", game.id))
        publish(SpectatorState(SpectatorState.Status.FINISHED, mode, game, "Next game in ${switchDelayMs / 1000}s"))
        pending?.cancel()
        pending = scheduler.schedule(switchDelayMs) {
            current = null
            search()
        }
    }

    private fun search() {
        if (!connected) return
        pending?.cancel()
        watchdog?.cancel()
        publish(SpectatorState(SpectatorState.Status.SEARCHING, mode, state.game, "Finding the next ${mode.label} game…"))
        send(Api.LIVE, liveBody())
        // The list is a subscription, but ask again if nothing usable arrives.
        pending = scheduler.schedule(RETRY_LIST_MS) {
            if (state.status == SpectatorState.Status.SEARCHING) search()
        }
    }

    private fun armWatchdog() {
        watchdog?.cancel()
        // Games have 3-minute main time; silence far beyond that means the game was lost track of.
        watchdog = scheduler.schedule(STALE_GAME_MS) {
            current?.id?.let {
                remember(it)
                send(Api.GAME_STOP, JSONObject().put("id", it))
            }
            current = null
            search()
        }
    }

    private fun remember(id: String) {
        recentlyFinished.addLast(id)
        while (recentlyFinished.size > 50) recentlyFinished.removeFirst()
    }

    private fun liveBody() = JSONObject().put("gtype", gtype).put("page", 0)

    private fun cancelTimers() {
        pending?.cancel(); pending = null
        watchdog?.cancel(); watchdog = null
    }

    private fun publish(s: SpectatorState) {
        state = s
        onState(s)
    }

    companion object {
        const val DEFAULT_SWITCH_DELAY_MS = 5_000L
        const val RETRY_LIST_MS = 15_000L
        const val STALE_GAME_MS = 5 * 60_000L
    }
}
