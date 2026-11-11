package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.model.GameClock
import com.naptimegames.goquestwidget.model.GameInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClockTest {
    private fun game(rem: List<Long>?) = GameInfo(
        id = "g", gtype = "go9", boardSize = 9, players = emptyList(), moves = listOf("B[ee]"),
        statusMarkers = emptyList(), finished = false, remainingMs = rem,
    )

    @Test
    fun formatsWithoutLeadingZeroUnderTenMinutes() {
        assertEquals("4:59", GameClock.format(299_000, padMinutes = false))
        assertEquals("0:05", GameClock.format(4_200, padMinutes = false)) // rounds up
        assertEquals("0:00", GameClock.format(0, padMinutes = false))
        assertEquals("04:59", GameClock.format(299_000, padMinutes = true))
        assertEquals("10:00", GameClock.format(600_000, padMinutes = true))
    }

    @Test
    fun padsWhenEitherPlayerHasTenMinutes() {
        assertFalse(GameClock.padMinutes(299_000, 599_999))
        assertTrue(GameClock.padMinutes(299_000, 600_000))
        assertFalse(GameClock.padMinutes(null, 120_000))
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
