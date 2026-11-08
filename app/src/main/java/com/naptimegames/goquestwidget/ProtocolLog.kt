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
        val clipped = if (text.length > MAX_LINE) text.take(MAX_LINE) + "…(${text.length} chars)" else text
        lines.addLast("${fmt.format(Date())} $tag $clipped")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
