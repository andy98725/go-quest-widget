package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.model.GameClock
import com.naptimegames.goquestwidget.model.GameClock.ClockFace
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
    fun formatsLikeAChronometer() {
        // Chronometer text comes from DateUtils.formatElapsedTime: "MM:SS", or "H:MM:SS" from
        // an hour. Fixed clocks must match it so both rows look the same.
        assertEquals("04:59", GameClock.format(299_000))
        assertEquals("00:05", GameClock.format(4_200)) // rounds up
        assertEquals("00:00", GameClock.format(0))
        assertEquals("10:00", GameClock.format(600_000))
        assertEquals("59:59", GameClock.format(3_599_000))
        assertEquals("1:05:09", GameClock.format(3_909_000))
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
    fun runningClockIsACountdownThatRoundsUp() {
        // Black to move, 3:00 left at t=1000. A Chronometer counting down shows (base - now) / 1000,
        // truncated, so the base sits 999 ms past the moment time runs out to round up like format().
        val face = GameClock.face(game(listOf(180_000, 150_000)), 0, toMove = 0, running = true, clockAtMs = 1_000, nowMs = 11_000)
        assertEquals(ClockFace.Countdown(chronometerBase = 181_999), face)
    }

    @Test
    fun countdownRestartWaitsForTheNextSecondChange() {
        // A Chronometer ticks every 1000 ms from when it starts. Starting it just as the shown
        // second changes keeps every later tick on a second change, so it never reads stale.
        assertEquals(999L, GameClock.msUntilSecondChange(chronometerBase = 181_999, nowMs = 11_000))
        assertEquals(0L, GameClock.msUntilSecondChange(chronometerBase = 181_999, nowMs = 11_999))
        assertEquals(1L, GameClock.msUntilSecondChange(chronometerBase = 181_999, nowMs = 11_998))
    }

    @Test
    fun waitingClockIsFixedTextInTheChronometersFormat() {
        val face = GameClock.face(game(listOf(97_000, 163_000)), 1, toMove = 0, running = true, clockAtMs = 0, nowMs = 0)
        assertEquals(ClockFace.Fixed("02:43"), face)
    }

    @Test
    fun runningClockStopsAtZero() {
        val g = game(listOf(5_000, 60_000))
        assertEquals(ClockFace.Fixed("00:00"), GameClock.face(g, 0, toMove = 0, running = true, clockAtMs = 0, nowMs = 9_000))
    }

    @Test
    fun finishedGameShowsBothClocksFixed() {
        val g = game(listOf(180_000, 150_000))
        assertEquals(ClockFace.Fixed("03:00"), GameClock.face(g, 0, toMove = 0, running = false, clockAtMs = 0, nowMs = 50_000))
    }

    @Test
    fun noClockDataGivesNoFace() {
        assertNull(GameClock.face(game(null), 0, toMove = 0, running = true, clockAtMs = 0, nowMs = 0))
    }

    @Test
    fun theOnlyFaceChangeBetweenMovesIsTheStopAtZero() {
        fun next(rem: Long) = GameClock.msUntilFaceChange(game(listOf(rem, 60_000)), toMove = 0, running = true, clockAtMs = 0, nowMs = 0)
        assertEquals(700_000L, next(700_000))
        assertEquals(300_000L, next(300_000))
        assertNull(next(0))
    }

    @Test
    fun nothingChangesWhenNoClockRuns() {
        assertNull(GameClock.msUntilFaceChange(game(listOf(300_000, 60_000)), toMove = 0, running = false, clockAtMs = 0, nowMs = 0))
        assertNull(GameClock.msUntilFaceChange(game(null), toMove = 0, running = true, clockAtMs = 0, nowMs = 0))
    }
}
