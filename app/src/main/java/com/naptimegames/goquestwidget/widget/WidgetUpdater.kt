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
import com.naptimegames.goquestwidget.model.GameInfo
import com.naptimegames.goquestwidget.model.Player

object WidgetUpdater {
    // Boards drawn for the current position, by pixel size. Widgets of similar size share one,
    // and status-only changes reuse it.
    private var cachedPosition: String? = null
    private val cachedBoards = HashMap<Int, Bitmap>()

    fun idleState(ctx: Context) = SpectatorState(Status.STOPPED, Prefs.boardMode(ctx))

    /** Redraws with the latest state, e.g. after a settings change. */
    fun refresh(ctx: Context) = update(ctx, SpectatorService.latestState ?: idleState(ctx))

    private const val GOQUEST_PACKAGE = "fm.wars.goquest"

    fun update(ctx: Context, state: SpectatorState) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, GoWidgetProvider::class.java))
        if (ids.isEmpty()) return
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
            mgr.updateAppWidget(id, build(ctx, state, px))
        }
    }

    private fun build(ctx: Context, state: SpectatorState, boardPx: Int): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_go)
        val game = state.game
        views.setInt(R.id.background, "setImageAlpha", Prefs.backgroundOpacity(ctx) * 255 / 100)
        views.setInt(R.id.board, "setImageAlpha", Prefs.boardOpacity(ctx) * 255 / 100)
        views.setImageViewBitmap(R.id.board, boardBitmap(state, boardPx))

        val live = game != null && state.status in setOf(Status.WATCHING, Status.FINISHED)
        if (live && game != null) {
            showPlayer(ctx, views, Side.BLACK, game.black, game, state.status)
            showPlayer(ctx, views, Side.WHITE, game.white, game, state.status)
            views.setViewVisibility(R.id.white_row, View.VISIBLE)
        } else {
            // No game to show: Black's row carries the status, White's row is hidden.
            views.setTextViewText(R.id.black_info, statusText(state))
            views.setViewVisibility(R.id.black_note, View.GONE)
            views.setViewVisibility(R.id.black_clock, View.GONE)
            views.setViewVisibility(R.id.white_row, View.GONE)
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

    private enum class Side(val index: Int, val info: Int, val note: Int, val clock: Int) {
        BLACK(0, R.id.black_info, R.id.black_note, R.id.black_clock),
        WHITE(1, R.id.white_info, R.id.white_note, R.id.white_clock),
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

    private fun showPlayer(ctx: Context, views: RemoteViews, side: Side, p: Player?, game: GameInfo, status: Status) {
        views.setTextViewText(side.info, "${stoneBullet(ctx, side)} ${playerLabel(p)}")

        val result = if (status == Status.FINISHED) resultNote(game, side) else null
        views.setTextViewText(side.note, result ?: "")
        views.setViewVisibility(side.note, if (result != null) View.VISIBLE else View.GONE)

        val remaining = game.remainingMs?.getOrNull(side.index)
        if (remaining == null || result != null) {
            views.setViewVisibility(side.clock, View.GONE)
            return
        }
        // Count down live on the side to move; the other clock stays still.
        val running = status == Status.WATCHING && game.toMove == side.index
        views.setChronometer(side.clock, SystemClock.elapsedRealtime() + remaining, null, running)
        views.setChronometerCountDown(side.clock, true)
        views.setViewVisibility(side.clock, View.VISIBLE)
    }

    /** "Wins · Resign" on the winner's row; the reason alone on Black's row if the winner is unknown. */
    private fun resultNote(game: GameInfo, side: Side): String? {
        val reason = game.resultReason
        return when (game.winnerIndex) {
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
        val position = "${game?.id}:${game?.moves?.size}:$size"
        if (position != cachedPosition) {
            cachedBoards.clear()
            cachedPosition = position
        }
        return cachedBoards.getOrPut(px) {
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
        Status.SEARCHING -> state.message ?: "Finding the top ${state.mode.label} game…"
        Status.OFFLINE -> state.message ?: "Offline"
        Status.WATCHING, Status.FINISHED -> ""
    }
}
