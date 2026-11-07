package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.RemoteUplinkWindow
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The duplex terminal's input rules, from the web client at harness 0.2.1-rc.2. */
class TerminalInputLedgerTest {

    private fun sequences(commands: List<kotlinx.serialization.json.JsonObject>) =
        commands.map { it.getValue("sequence").jsonPrimitive.long }

    @Test
    fun `commands are numbered from one and keep their order across writes and resizes`() {
        val ledger = TerminalInputLedger(maxInputBytes = 1024, maxBufferedBytes = 4096)
        assertTrue(ledger.write("ls"))
        assertTrue(ledger.resize(100, 30))
        assertTrue(ledger.write("\r"))
        val sent = ledger.drain()
        assertEquals(listOf(1L, 2L, 3L), sequences(sent))
        assertEquals(listOf("write", "resize", "write"), sent.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("ls", sent[0].getValue("data").jsonPrimitive.content)
        assertEquals(100, sent[1].getValue("cols").jsonPrimitive.content.toInt())
    }

    @Test
    fun `the window holds commands back until an ack frees room`() {
        val ledger = TerminalInputLedger(maxInputBytes = 1024, maxBufferedBytes = null)
        val one = """{"type":"write","sequence":1,"data":"a"}""".toByteArray().size
        ledger.window = RemoteUplinkWindow(maxBytes = one + 10, frameOverheadBytes = 10)
        assertTrue(ledger.write("a"))
        assertTrue(ledger.write("b"))
        assertEquals(listOf(1L), sequences(ledger.drain()))
        assertEquals(1, ledger.waiting)
        // Nothing more fits until the first is acknowledged.
        assertTrue(ledger.drain().isEmpty())
        assertTrue(ledger.acknowledge(1))
        assertEquals(listOf(2L), sequences(ledger.drain()))
    }

    @Test
    fun `input past the raw byte cap is refused, not queued`() {
        val ledger = TerminalInputLedger(maxInputBytes = 4, maxBufferedBytes = null)
        assertTrue(ledger.write("abc"))
        assertFalse(ledger.write("de"))
        // The refused command took no sequence number, so the next accepted one is still 2.
        assertTrue(ledger.acknowledge(1).not()) // nothing has been sent yet
        assertEquals(listOf(1L), sequences(ledger.drain()))
        assertTrue(ledger.acknowledge(1))
        assertTrue(ledger.write("de"))
        assertEquals(listOf(2L), sequences(ledger.drain()))
    }

    @Test
    fun `an out-of-order ack is a protocol violation`() {
        val ledger = TerminalInputLedger(maxInputBytes = 1024, maxBufferedBytes = null)
        ledger.write("a")
        ledger.write("b")
        ledger.drain()
        assertFalse(ledger.acknowledge(2))
        assertTrue(ledger.acknowledge(1))
        assertTrue(ledger.acknowledge(2))
        assertEquals(0, ledger.inFlight)
    }

    @Test
    fun `the buffered byte cap counts encoded frames, overhead included`() {
        val frame = """{"type":"write","sequence":1,"data":"a"}""".toByteArray().size
        val ledger = TerminalInputLedger(maxInputBytes = 1024, maxBufferedBytes = frame + 5)
        ledger.window = RemoteUplinkWindow(maxBytes = 10_000, frameOverheadBytes = 5)
        assertTrue(ledger.write("a"))
        assertFalse(ledger.write("b"))
    }
}
