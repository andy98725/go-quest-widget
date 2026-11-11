package com.naptimegames.goquestwidget.model

/**
 * Remaining time on each player's clock, counted down locally between server updates.
 * [clockAtMs] is when the server's `remTime` snapshot arrived (same timebase as `nowMs`).
 */
object GameClock {
    private const val TEN_MINUTES_MS = 10 * 60_000L

    /** Remaining ms for player [index] (0 = Black), or null if the server didn't send clocks. */
    fun remaining(game: GameInfo, index: Int, running: Boolean, clockAtMs: Long, nowMs: Long): Long? {
        val snapshot = game.remainingMs?.getOrNull(index) ?: return null
        val elapsed = if (running) (nowMs - clockAtMs).coerceAtLeast(0) else 0
        return (snapshot - elapsed).coerceAtLeast(0)
    }

    /**
     * "4:59", or "04:59" when either player has 10 minutes or more, so both clocks keep
     * the same width.
     */
    fun format(ms: Long, padMinutes: Boolean): String {
        val totalSeconds = (ms + 999) / 1000 // round up: show 0:00 only when time is really out
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (padMinutes) "%02d:%02d".format(minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    fun padMinutes(vararg remaining: Long?): Boolean = remaining.any { it != null && it >= TEN_MINUTES_MS }

    /** Delay until the running clock's display next changes, for scheduling the next tick. */
    fun msUntilNextTick(remainingMs: Long): Long {
        val r = remainingMs % 1000
        return if (r == 0L) 1000 else r
    }
}
