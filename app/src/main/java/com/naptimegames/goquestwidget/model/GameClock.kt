package com.naptimegames.goquestwidget.model

/**
 * Remaining time on each player's clock, counted down locally between server updates.
 * [clockAtMs] is when the server's `remTime` snapshot arrived (same timebase as `nowMs`).
 */
object GameClock {
    /** Remaining ms for player [index] (0 = Black), or null if the server didn't send clocks. */
    fun remaining(game: GameInfo, index: Int, running: Boolean, clockAtMs: Long, nowMs: Long): Long? {
        val snapshot = game.remainingMs?.getOrNull(index) ?: return null
        val elapsed = if (running) (nowMs - clockAtMs).coerceAtLeast(0) else 0
        return (snapshot - elapsed).coerceAtLeast(0)
    }

    /**
     * "4:59", "10:00", "75:00": minutes and seconds, no leading zero. A widget Chronometer
     * can't drop the leading zero, so the clocks are text the service updates each second.
     */
    fun format(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000 // round up: show 0:00 only when time is really out
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    /** Delay until the running clock's display next changes, for scheduling the next tick. */
    fun msUntilNextTick(remainingMs: Long): Long {
        val r = remainingMs % 1000
        return if (r == 0L) 1000 else r
    }
}
