package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.model.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardTest {
    @Test
    fun placesStonesFromSgf() {
        val b = Board.fromMoves(9, listOf("B[ee]", "W[ed]"))
        assertEquals(Board.BLACK, b[4, 4])
        assertEquals(Board.WHITE, b[4, 3])
        assertEquals(4 to 3, b.lastMove)
    }

    @Test
    fun capturesSingleStoneInCorner() {
        // White at aa, Black plays ba then ab.
        val b = Board.fromMoves(9, listOf("B[ba]", "W[aa]", "B[ab]"))
        assertEquals(Board.EMPTY, b[0, 0])
        assertEquals(1, b.capturedByBlack)
    }

    @Test
    fun capturesGroup() {
        // White pair at dd/ed surrounded on all six sides.
        val moves = listOf("W[dd]", "B[cd]", "W[ed]", "B[fd]", "B[dc]", "B[ec]", "B[de]", "B[ee]")
        val b = Board.fromMoves(9, moves)
        assertEquals(Board.EMPTY, b[3, 3])
        assertEquals(Board.EMPTY, b[4, 3])
        assertEquals(2, b.capturedByBlack)
    }

    @Test
    fun captureTakesPrecedenceOverSuicide() {
        // Black aa has no liberties of its own but captures the white stones at ba and ab.
        val moves = listOf("W[ba]", "B[ca]", "W[ab]", "B[bb]", "B[ac]", "B[aa]")
        val b = Board.fromMoves(9, moves)
        assertEquals(Board.BLACK, b[0, 0])
        assertEquals(Board.EMPTY, b[1, 0])
        assertEquals(Board.EMPTY, b[0, 1])
    }

    @Test
    fun suicideRemovesOwnGroup() {
        val b = Board.fromMoves(9, listOf("W[ba]", "W[ab]", "B[aa]"))
        assertEquals(Board.EMPTY, b[0, 0])
    }

    @Test
    fun passesAndMarkersAreSkipped() {
        val b = Board.fromMoves(9, listOf("B[ee]", "W[]", null, "B[tt]"))
        assertEquals(Board.BLACK, b[4, 4])
        assertNull(b.lastMove)
    }
}
