package com.naptimegames.goquestwidget.model

/** Minimal Go board: placement plus capture of groups without liberties. */
class Board(val size: Int) {
    private val cells = IntArray(size * size)

    var lastMove: Pair<Int, Int>? = null
        private set

    var capturedByBlack = 0
        private set
    var capturedByWhite = 0
        private set

    operator fun get(x: Int, y: Int): Int = cells[y * size + x]

    fun play(color: Int, x: Int, y: Int) {
        require(color == BLACK || color == WHITE) { "bad color $color" }
        if (x !in 0 until size || y !in 0 until size) return
        cells[y * size + x] = color
        val opponent = -color
        var captured = 0
        for ((nx, ny) in neighbours(x, y)) {
            if (this[nx, ny] == opponent) {
                val group = groupAt(nx, ny)
                if (group.liberties == 0) captured += removeGroup(group.stones)
            }
        }
        // Suicide is illegal on GoQuest; handled anyway so the board never shows a dead group.
        val own = groupAt(x, y)
        if (own.liberties == 0) {
            val n = removeGroup(own.stones)
            if (color == BLACK) capturedByWhite += n else capturedByBlack += n
        }
        if (color == BLACK) capturedByBlack += captured else capturedByWhite += captured
        lastMove = x to y
    }

    fun pass() {
        lastMove = null
    }

    private class Group(val stones: List<Int>, val liberties: Int)

    private fun groupAt(x: Int, y: Int): Group {
        val color = this[x, y]
        val seen = HashSet<Int>()
        val libs = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.add(y * size + x)
        seen.add(y * size + x)
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            for ((nx, ny) in neighbours(idx % size, idx / size)) {
                val nIdx = ny * size + nx
                when (cells[nIdx]) {
                    EMPTY -> libs.add(nIdx)
                    color -> if (seen.add(nIdx)) stack.add(nIdx)
                }
            }
        }
        return Group(seen.toList(), libs.size)
    }

    private fun removeGroup(stones: List<Int>): Int {
        stones.forEach { cells[it] = EMPTY }
        return stones.size
    }

    private fun neighbours(x: Int, y: Int): List<Pair<Int, Int>> {
        // Not buildList: inside it, `size` would mean the list's size.
        val last = size - 1
        val out = ArrayList<Pair<Int, Int>>(4)
        if (x > 0) out.add(x - 1 to y)
        if (x < last) out.add(x + 1 to y)
        if (y > 0) out.add(x to y - 1)
        if (y < last) out.add(x to y + 1)
        return out
    }

    companion object {
        const val EMPTY = 0
        const val BLACK = 1
        const val WHITE = -1

        /**
         * Replays GoQuest's SGF-style moves ("B[ee]", "W[]" for a pass). Entries
         * that are not moves (e.g. {"s":"LOSE:RESIGN"}) are passed as null and skipped.
         */
        fun fromMoves(size: Int, moves: List<String?>): Board {
            val board = Board(size)
            for (m in moves) {
                val move = m ?: continue
                val match = SGF_MOVE.matchEntire(move.trim()) ?: continue
                val color = if (match.groupValues[1] == "B") BLACK else WHITE
                val coord = match.groupValues[2]
                if (coord.length != 2 || (coord == "tt" && size <= 19)) {
                    board.pass()
                    continue
                }
                board.play(color, coord[0] - 'a', coord[1] - 'a')
            }
            return board
        }

        private val SGF_MOVE = Regex("""([BW])\[([a-z]*)]""")
    }
}
