package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.protocol.GoQuestClient
import com.naptimegames.goquestwidget.protocol.Protocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs the real client over TCP against a local server that replays a server transcript. */
class GoQuestClientTest {
    @Test
    fun handshakeThenMessagesThenClose() {
        val transcript = javaClass.classLoader!!.getResourceAsStream("server_transcript.txt")!!.bufferedReader().readLines()
        val server = ServerSocket(0)
        val received = LinkedBlockingQueue<String>()
        thread(isDaemon = true) {
            server.accept().use { s ->
                val reader = s.getInputStream().bufferedReader()
                received += reader.readLine()          // client's start
                val out = s.getOutputStream()
                transcript.forEach { out.write((it + "\n").toByteArray()) }
                out.flush()
                received += reader.readLine()          // a message sent after onReady
            }
        }

        var ready: Protocol.ApiTable? = null
        val names = mutableListOf<String>()
        val closed = CountDownLatch(1)
        val client = GoQuestClient("127.0.0.1", server.localPort, JSONObject().put("gtype", "go9"),
            object : GoQuestClient.Listener {
                override fun onReady(api: Protocol.ApiTable, error: String?) {
                    ready = api
                    clientRef!!.send(api.id(Protocol.Api.LIVE), JSONObject().put("gtype", "go9").put("page", 0))
                }
                override fun onMessage(name: String, body: JSONObject) { names += name }
                override fun onClosed(reason: String, detail: String?) = closed.countDown()
            })
        clientRef = client
        client.connect()

        val start = received.poll(5, TimeUnit.SECONDS)!!
        assertTrue(start, start.startsWith("start {"))
        assertEquals("""b878053d {"gtype":"go9","page":0}""".let { JSONObject(it.substringAfter(' ')).toString() },
            JSONObject(received.poll(5, TimeUnit.SECONDS)!!.substringAfter(' ')).toString())
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals("8d7a2124", ready!!.id(Protocol.Api.GAME_POSITION))
        assertEquals(transcript.size - 1, names.size)
        server.close()
    }

    private companion object { var clientRef: GoQuestClient? = null }
}
