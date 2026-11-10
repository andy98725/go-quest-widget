package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.protocol.GoQuestClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class LogAndErrorsTest {
    @Test
    fun compactsMoveLists() {
        val line = """8d7a2124 {"id":"g","position":{"moves":[{"t":1,"m":"B[jd]"},{"t":2038,"m":"W[ek]"},{"t":21179,"m":"B[fh]"}],"size":13}}"""
        assertEquals(
            """8d7a2124 {"id":"g","position":{"moves":[…3 moves, last B[fh]…],"size":13}}""",
            ProtocolLog.compact(line),
        )
    }

    @Test
    fun compactsResultsAndEmptyLists() {
        val result = """1f566a1a {"position":{"moves":[{"m":"B[df]"},{"t":4786,"s":"LOSE:RESIGN"}],"size":9},"finished":true}"""
        assertTrue(ProtocolLog.compact(result).contains("[…1 move, last B[df], result LOSE:RESIGN…]"))
        assertEquals("""x {"moves":[…0 moves…]}""", ProtocolLog.compact("""x {"moves":[]}"""))
    }

    @Test
    fun compactsTruncatedLinesAndLeavesOthersAlone() {
        // A line cut off mid-array: summarise the complete moves up to the cut.
        assertEquals("""x {"moves":[…1 move, last B[aa]…""", ProtocolLog.compact("""x {"moves":[{"m":"B[aa]"},{"t":5080,"m":"W[ad"""))
        val status = """f19683a8 {"connected":438,"games":134}"""
        assertEquals(status, ProtocolLog.compact(status))
    }

    @Test
    fun readableConnectionErrors() {
        assertEquals("Network changed", GoQuestClient.describe(SocketException("Software caused connection abort")))
        assertEquals("Connection dropped", GoQuestClient.describe(SocketException("Connection reset")))
        assertEquals("No network", GoQuestClient.describe(UnknownHostException("ec2-…")))
        assertEquals("Can't reach the server", GoQuestClient.describe(ConnectException("failed to connect")))
        assertEquals("Server stopped responding", GoQuestClient.describe(SocketTimeoutException("Read timed out")))
        assertFalse(GoQuestClient.describe(RuntimeException("weird")).contains("weird"))
    }
}
