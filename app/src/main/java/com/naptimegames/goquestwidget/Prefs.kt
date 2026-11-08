package com.naptimegames.goquestwidget

import android.content.Context
import com.naptimegames.goquestwidget.model.BoardMode
import com.naptimegames.goquestwidget.protocol.Protocol

object Prefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun boardMode(ctx: Context): BoardMode {
        val p = prefs(ctx)
        BoardMode.fromKey(p.getString("board_mode", null))?.let { return it }
        // Before 0.4 there was only "board_size": 13 meant the whole go13 list.
        return if (p.getInt("board_size", 9) == 13) BoardMode.THIRTEEN_AND_NINETEEN else BoardMode.NINE
    }

    fun setBoardMode(ctx: Context, mode: BoardMode) = prefs(ctx).edit().putString("board_mode", mode.key).apply()

    const val MAX_TOP_N = 25 // one page of the live list

    /** How many of the top live games to consider when picking the next game. */
    fun topN(ctx: Context): Int = prefs(ctx).getInt("top_n", 1).coerceIn(1, MAX_TOP_N)
    fun setTopN(ctx: Context, n: Int) = prefs(ctx).edit().putInt("top_n", n.coerceIn(1, MAX_TOP_N)).apply()

    /** Board (wood, lines and stones) opacity, 0-100. */
    fun boardOpacity(ctx: Context): Int = prefs(ctx).getInt("board_opacity", 100).coerceIn(0, 100)
    fun setBoardOpacity(ctx: Context, percent: Int) =
        prefs(ctx).edit().putInt("board_opacity", percent.coerceIn(0, 100)).apply()

    const val MAX_POST_GAME_DELAY_S = 600

    /** Seconds to keep showing a finished game before moving on. */
    fun postGameDelaySeconds(ctx: Context): Int = prefs(ctx).getInt("post_game_delay", 5).coerceIn(0, MAX_POST_GAME_DELAY_S)
    fun setPostGameDelaySeconds(ctx: Context, s: Int) =
        prefs(ctx).edit().putInt("post_game_delay", s.coerceIn(0, MAX_POST_GAME_DELAY_S)).apply()

    /** Widget background opacity, 0-100. */
    fun backgroundOpacity(ctx: Context): Int = prefs(ctx).getInt("bg_opacity", 90).coerceIn(0, 100)
    fun setBackgroundOpacity(ctx: Context, percent: Int) =
        prefs(ctx).edit().putInt("bg_opacity", percent.coerceIn(0, 100)).apply()

    /** Random per-install id, sent as `vendor_id` like the official app does. */
    fun vendorId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("vendor_id", null)?.let { return it }
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val id = (1..10).map { alphabet.random() }.joinToString("")
        p.edit().putString("vendor_id", id).apply()
        return id
    }

    /** Game server as host:port; the default comes from a capture of the official app. */
    fun serverAddress(ctx: Context): String =
        prefs(ctx).getString("server", null)?.takeIf { it.isNotBlank() } ?: defaultServer()

    fun setServerAddress(ctx: Context, value: String) =
        prefs(ctx).edit().putString("server", value.trim().takeIf { it.isNotEmpty() && it != defaultServer() }).apply()

    fun defaultServer() = "${Protocol.DEFAULT_HOST}:${Protocol.DEFAULT_PORT}"

    fun server(ctx: Context): Pair<String, Int> {
        val raw = serverAddress(ctx)
        val host = raw.substringBefore(':')
        val port = raw.substringAfter(':', "").toIntOrNull() ?: Protocol.DEFAULT_PORT
        return host to port
    }

    /** Whether the user wants spectating on (cleared by Stop, set by Start). */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
}
