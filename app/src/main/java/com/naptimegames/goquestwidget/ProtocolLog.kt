package com.naptimegames.goquestwidget

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of recent frames, shown in the app for debugging and protocol capture. */
object ProtocolLog {
    private const val MAX = 400
    private const val MAX_LINE = 2_000
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(tag: String, text: String) {
        val short = compact(text)
        val clipped = if (short.length > MAX_LINE) short.take(MAX_LINE) + "…(${short.length} chars)" else short
        lines.addLast("${fmt.format(Date())} $tag $clipped")
        while (lines.size > MAX) lines.removeFirst()
    }

    private val MOVE = Regex(""""m":"([^"]*)"""")
    private val MARKER = Regex(""""s":"([^"]*)"""")
    private const val MOVES_KEY = "\"moves\":["

    /**
     * Game messages repeat the whole move list on every move; replace it with a summary so
     * the log stays readable and covers more time: `"moves":[…41 moves, last B[fh]…]`.
     * Lines cut short (no closing bracket) are summarised up to where they end.
     */
    fun compact(text: String): String {
        val out = StringBuilder()
        var from = 0
        while (true) {
            val start = text.indexOf(MOVES_KEY, from)
            if (start < 0) break
            val body = start + MOVES_KEY.length
            val end = closingBracket(text, body) // index of the matching ']', or -1 if cut off
            val array = if (end < 0) text.substring(body) else text.substring(body, end)
            out.append(text, from, start).append(MOVES_KEY).append('…').append(summary(array)).append('…')
            if (end < 0) return out.toString()
            out.append(']')
            from = end + 1
        }
        return out.append(text, from, text.length).toString()
    }

    /** Finds the ']' closing an array that starts at [from], skipping brackets inside strings. */
    private fun closingBracket(s: String, from: Int): Int {
        var depth = 0
        var inString = false
        var i = from
        while (i < s.length) {
            val c = s[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '[' -> depth++
                c == ']' -> if (depth == 0) return i else depth--
            }
            i++
        }
        return -1
    }

    private fun summary(array: String): String {
        val moves = MOVE.findAll(array).map { it.groupValues[1] }.toList()
        val marker = MARKER.findAll(array).lastOrNull()?.groupValues?.get(1)
        return listOfNotNull(
            if (moves.size == 1) "1 move" else "${moves.size} moves",
            moves.lastOrNull()?.let { "last $it" },
            marker?.let { "result $it" },
        ).joinToString(", ")
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
