package com.naptimegames.goquestwidget.protocol

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Line-based TCP client for GoQuest's game server. Callbacks arrive on the reader
 * thread; the caller hops to its own thread.
 */
class GoQuestClient(
    private val host: String,
    private val port: Int,
    private val startBody: JSONObject,
    private val listener: Listener,
) {
    interface Listener {
        /**
         * The server answered `start`; [api] resolves message ids. [error] is the server's
         * complaint if it sent one instead of the table (spectating still works).
         */
        fun onReady(api: Protocol.ApiTable, error: String?)
        fun onMessage(name: String, body: JSONObject)
        fun onClosed(reason: String)
        fun onLine(outgoing: Boolean, text: String) {}
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    private val closedByUs = AtomicBoolean(false)
    private val closeReported = AtomicBoolean(false)
    // Writes go through one thread so callers (main thread) never block on the network.
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "goquest-writer").apply { isDaemon = true } }

    fun connect() {
        Thread({ run() }, "goquest-reader").apply { isDaemon = true }.start()
    }

    private fun run() {
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            // The server pushes a status line every ~5s, so silence this long means a dead link.
            s.soTimeout = READ_TIMEOUT_MS
            s.tcpNoDelay = true
            out = s.getOutputStream()
            if (closedByUs.get()) return s.close()
            send(Protocol.START, startBody)

            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8), 64 * 1024)
            var ready = false
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                listener.onLine(false, line)
                val msg = Protocol.parseLine(line) ?: continue
                if (!ready && msg.name == Protocol.START) {
                    ready = true
                    listener.onReady(Protocol.ApiTable.fromStart(msg.body), msg.body.optString("error").ifEmpty { null })
                } else {
                    listener.onMessage(msg.name, msg.body)
                }
            }
            closed("Server closed the connection")
        } catch (e: SocketTimeoutException) {
            closed("Server stopped responding")
        } catch (e: Exception) {
            closed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { socket?.close() }
        }
    }

    fun send(name: String, body: JSONObject) = write(Protocol.line(name, body))

    fun keepAlive() = write(Protocol.KEEPALIVE)

    private fun write(text: String) {
        if (text.isNotBlank()) listener.onLine(true, text.trimEnd())
        if (writer.isShutdown) return
        runCatching {
            writer.execute {
                try {
                    out?.apply { write(text.toByteArray(Charsets.UTF_8)); flush() }
                } catch (e: Exception) {
                    runCatching { socket?.close() } // the reader thread reports the failure
                }
            }
        }
    }

    fun close() {
        closedByUs.set(true)
        writer.shutdown()
        runCatching { socket?.close() }
    }

    private fun closed(reason: String) {
        if (!closedByUs.get() && closeReported.compareAndSet(false, true)) listener.onClosed(reason)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 45_000
    }
}
