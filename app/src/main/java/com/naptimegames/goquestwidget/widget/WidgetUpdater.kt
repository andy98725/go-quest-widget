package com.naptimegames.goquestwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.naptimegames.goquestwidget.Prefs
import com.naptimegames.goquestwidget.R
import com.naptimegames.goquestwidget.SpectatorService
import com.naptimegames.goquestwidget.SpectatorState
import com.naptimegames.goquestwidget.SpectatorState.Status
import com.naptimegames.goquestwidget.model.Board
import com.naptimegames.goquestwidget.model.GameClock
import com.naptimegames.goquestwidget.model.GameInfo
import com.naptimegames.goquestwidget.model.Player
import com.naptimegames.goquestwidget.model.WidgetSettings

object WidgetUpdater {
    // Recently drawn boards, keyed by position and pixel size: widgets showing the same game at a
    // similar size share one, and status-only changes reuse it. Small, since each group only
    // needs its latest position.
    private val cachedBoards = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
    }

    fun idleState(ctx: Context, widgetId: Int) = SpectatorState(Status.STOPPED, Prefs.widget(ctx, widgetId).mode)

    private const val GOQUEST_PACKAGE = "fm.wars.goquest"

    fun widgetIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, GoWidgetProvider::class.java))

    /** Redraws every widget with its latest state, e.g. after a settings change. */
    fun refresh(ctx: Context) = refresh(ctx, widgetIds(ctx).toList())

    fun refresh(ctx: Context, ids: Collection<Int>) {
        for (id in ids) update(ctx, listOf(id), SpectatorService.stateFor(id) ?: idleState(ctx, id))
    }

    /** Shows [state] on the given widgets, each with its own size and opacity settings. */
    fun update(ctx: Context, ids: Collection<Int>, state: SpectatorState) {
        if (ids.isEmpty()) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val density = ctx.resources.displayMetrics.density
        for (id in ids) {
            val o = mgr.getAppWidgetOptions(id)
            val px = WidgetSizing.boardPx(
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT),
                density,
            )
            mgr.updateAppWidget(id, build(ctx, state, px, Prefs.widget(ctx, id)))
        }
    }

    private fun build(ctx: Context, state: SpectatorState, boardPx: Int, settings: WidgetSettings): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_go)
        val game = state.game
        views.setInt(R.id.background, "setImageAlpha", settings.backgroundOpacity * 255 / 100)
        views.setInt(R.id.board, "setImageAlpha", settings.boardOpacity * 255 / 100)
        views.setImageViewBitmap(R.id.board, boardBitmap(state, boardPx))

        val live = game != null && state.status in setOf(Status.WATCHING, Status.FINISHED)
        if (live && game != null) {
            val board = Board.fromMoves(game.boardSize, game.moves)
            showPlayer(ctx, views, Side.BLACK, game.black, state, board.capturedByBlack)
            showPlayer(ctx, views, Side.WHITE, game.white, state, board.capturedByWhite)
            views.setViewVisibility(R.id.white_row, View.VISIBLE)
        } else {
            // No game to show: Black's row carries the status. White's row stays in the layout
            // (invisible) so the board keeps its size and position.
            views.setTextViewText(R.id.black_info, statusText(state))
            views.setViewVisibility(R.id.black_note, View.GONE)
            views.setViewVisibility(R.id.black_captures, View.GONE)
            views.setViewVisibility(R.id.black_clock, View.GONE)
            views.setViewVisibility(R.id.white_row, View.INVISIBLE)
        }

        // GoQuest has no known link to a specific game, so open the app itself.
        val goQuest = ctx.packageManager.getLaunchIntentForPackage(GOQUEST_PACKAGE)
        val boardTap = if (live && goQuest != null) {
            PendingIntent.getActivity(ctx, 10, goQuest, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else startIntent(ctx)
        views.setOnClickPendingIntent(R.id.board, boardTap)
        views.setOnClickPendingIntent(R.id.black_row, startIntent(ctx))
        return views
    }

    private enum class Side(val index: Int, val info: Int, val note: Int, val captures: Int, val clock: Int) {
        BLACK(0, R.id.black_info, R.id.black_note, R.id.black_captures, R.id.black_clock),
        WHITE(1, R.id.white_info, R.id.white_note, R.id.white_captures, R.id.white_clock);

        val opponent: Side get() = if (this == BLACK) WHITE else BLACK
    }

    /**
     * Text bullets take the text colour: on light text "●" looks like a white stone and "○"
     * (dark centre) like a black one, and the reverse on dark text. Pick by the text's lightness.
     */
    private fun stoneBullet(ctx: Context, side: Side): String {
        val c = ctx.getColor(R.color.widget_text)
        val luminance = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255
        val filledLooksWhite = luminance > 0.5
        return if ((side == Side.WHITE) == filledLooksWhite) "●" else "○"
    }

    private fun showPlayer(ctx: Context, views: RemoteViews, side: Side, p: Player?, state: SpectatorState, captures: Int) {
        val game = state.game ?: return
        views.setTextViewText(side.info, "${stoneBullet(ctx, side)} ${playerLabel(p)}")

        val result = if (state.status == Status.FINISHED) resultNote(game, side) else null
        views.setTextViewText(side.note, result ?: "")
        views.setViewVisibility(side.note, if (result != null) View.VISIBLE else View.GONE)

        // Prisoners: the captured stones' bullet and a count, e.g. "●3" for Black taking 3 white stones.
        views.setTextViewText(side.captures, "${stoneBullet(ctx, side.opponent)}$captures")
        views.setViewVisibility(side.captures, if (captures > 0) View.VISIBLE else View.GONE)

        val text = if (result == null) clockText(state, side) else null
        views.setTextViewText(side.clock, text ?: "")
        views.setViewVisibility(side.clock, if (text != null) View.VISIBLE else View.GONE)
    }

    /** Both clocks as text right now, or null for a side without clock data. */
    private fun clockText(state: SpectatorState, side: Side, now: Long = SystemClock.elapsedRealtime()): String? {
        val game = state.game ?: return null
        val running = state.status == Status.WATCHING
        fun rem(s: Side) = GameClock.remaining(game, s.index, running && game.toMove == s.index, state.clockAtMs, now)
        val mine = rem(side) ?: return null
        return GameClock.format(mine, GameClock.padMinutes(rem(Side.BLACK), rem(Side.WHITE)))
    }

    /**
     * Updates only the clock text on [ids] (no board image), for the per-second tick. Returns
     * how long until the running clock's display next changes, or null if nothing is running.
     */
    fun tickClocks(ctx: Context, ids: Collection<Int>, state: SpectatorState): Long? {
        val game = state.game ?: return null
        if (state.status != Status.WATCHING || game.remainingMs == null) return null
        val now = SystemClock.elapsedRealtime()
        val views = RemoteViews(ctx.packageName, R.layout.widget_go)
        for (side in Side.entries) clockText(state, side, now)?.let { views.setTextViewText(side.clock, it) }
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids) mgr.partiallyUpdateAppWidget(id, views)
        val running = GameClock.remaining(game, game.toMove, true, state.clockAtMs, now) ?: return null
        return if (running > 0) GameClock.msUntilNextTick(running) else null
    }


    /** "Wins · Resign" on the winner's row, "Draw" on both, or the result on Black's row if the winner is unknown. */
    private fun resultNote(game: GameInfo, side: Side): String? {
        if (game.isDraw) return "Draw" // on both rows
        val reason = game.resultReason
        return when (game.winner) {
            side.index -> listOfNotNull("Wins", reason).joinToString(" · ")
            null -> if (side == Side.BLACK) game.resultLabel else null
            else -> null
        }
    }

    private fun startIntent(ctx: Context): PendingIntent = PendingIntent.getForegroundService(
        ctx, 13, SpectatorService.intent(ctx, SpectatorService.ACTION_START),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun boardBitmap(state: SpectatorState, px: Int): Bitmap {
        val game = state.game
        val size = game?.boardSize ?: state.mode.defaultBoardSize
        val key = "${game?.id}:${game?.moves?.size}:$size:$px"
        return cachedBoards.getOrPut(key) {
            val board = game?.let { Board.fromMoves(it.boardSize, it.moves) }
            BoardRenderer.render(board, size, px)
        }
    }

    private fun playerLabel(p: Player?): String {
        if (p == null) return "—"
        val details = listOfNotNull(p.rankLabel, p.rating?.let { "%.0f".format(it) }).joinToString(" · ")
        return if (details.isEmpty()) p.name else "${p.name} ($details)"
    }

    private fun statusText(state: SpectatorState): String = when (state.status) {
        Status.STOPPED -> "Tap to start spectating ${state.mode.label}"
        Status.CONNECTING -> "Connecting…"
        Status.SEARCHING -> state.message ?: "Finding the next ${state.mode.label} game…"
        Status.OFFLINE -> state.message ?: "Offline"
        Status.WATCHING, Status.FINISHED -> ""
    }
}
