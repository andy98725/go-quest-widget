package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.model.GameClock
import com.naptimegames.goquestwidget.model.GameInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameClockTest {
    private fun game(rem: List<Long>?) = GameInfo(
        id = "g", gtype = "go9", boardSize = 9, players = emptyList(), moves = listOf("B[ee]"),
        statusMarkers = emptyList(), finished = false, remainingMs = rem,
    )

    @Test
    fun formatsAsMinutesAndSecondsWithoutALeadingZero() {
        assertEquals("4:59", GameClock.format(299_000))
        assertEquals("0:05", GameClock.format(4_200)) // rounds up
        assertEquals("0:00", GameClock.format(0))
        assertEquals("10:00", GameClock.format(600_000))
        assertEquals("75:00", GameClock.format(4_500_000))
    }

    @Test
    fun onlyTheRunningClockCountsDown() {
        val g = game(listOf(180_000, 150_000))
        assertEquals(170_000L, GameClock.remaining(g, 0, running = true, clockAtMs = 1_000, nowMs = 11_000))
        assertEquals(150_000L, GameClock.remaining(g, 1, running = false, clockAtMs = 1_000, nowMs = 11_000))
        assertEquals(0L, GameClock.remaining(g, 0, running = true, clockAtMs = 0, nowMs = 999_000)) // never negative
        assertNull(GameClock.remaining(game(null), 0, running = true, clockAtMs = 0, nowMs = 0))
    }

    @Test
    fun nextTickLandsOnTheSecond() {
        assertEquals(400L, GameClock.msUntilNextTick(170_400))
        assertEquals(1000L, GameClock.msUntilNextTick(170_000))
    }
}
