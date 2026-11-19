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
     * "04:59", or "1:05:09" from an hour: the format a widget Chronometer uses
     * (DateUtils.formatElapsedTime), so fixed and running clocks look the same.
     */
    fun format(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000 // round up: show 00:00 only when time is really out
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60 % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    /** How one side's clock appears on the widget. */
    sealed interface ClockFace {
        /** Text that stays as it is. */
        data class Fixed(val text: String) : ClockFace

        /** A Chronometer counting down to [chronometerBase] (elapsedRealtime). The launcher ticks it. */
        data class Countdown(val chronometerBase: Long) : ClockFace
    }

    /**
     * Player [index]'s clock face. [toMove] is the side to move; its clock counts down while
     * [running]. Null if the server didn't send clocks.
     */
    fun face(game: GameInfo, index: Int, toMove: Int, running: Boolean, clockAtMs: Long, nowMs: Long): ClockFace? {
        val mine = remaining(game, index, running && index == toMove, clockAtMs, nowMs) ?: return null
        if (!running || index != toMove || mine == 0L) return ClockFace.Fixed(format(mine))
        // A counting-down Chronometer shows (base - now) / 1000, truncated. Adding 999 ms makes
        // it round up like format().
        return ClockFace.Countdown(clockAtMs + game.remainingMs!![index] + 999)
    }

    /**
     * Time until a countdown's shown second next changes. A Chronometer ticks every 1000 ms from
     * when it starts, so starting it at that moment keeps each tick on a second change.
     */
    fun msUntilSecondChange(chronometerBase: Long, nowMs: Long): Long = Math.floorMod(chronometerBase - nowMs, 1000L)

    /** Time until the running clock reaches 00:00 and must stop, or null if no clock runs. */
    fun msUntilFaceChange(game: GameInfo, toMove: Int, running: Boolean, clockAtMs: Long, nowMs: Long): Long? {
        if (!running) return null
        val rem = remaining(game, toMove, true, clockAtMs, nowMs) ?: return null
        return rem.takeIf { it > 0 }
    }
}
