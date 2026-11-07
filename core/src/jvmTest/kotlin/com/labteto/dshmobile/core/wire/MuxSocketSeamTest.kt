package com.labteto.dshmobile.core.wire

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mux depends on [MuxSocket] and nothing else.
 *
 * `WsChannel` is the only implementation that ships today, and every other test stands in for it by
 * subclassing it, which would keep passing even if the mux quietly grew a dependency on OkHttp.
 * These tests drive a mux with a socket that is *only* a [MuxSocket] — no OkHttp client, no subclass
 * — which is the shape the iOS carrier will take.
 */
class MuxSocketSeamTest {

    private class BareSocket(private val sink: WsChannelSink) : MuxSocket {
        val sent = CopyOnWriteArrayList<String>()
        var starts = 0
        var closes = 0

        override fun start() {
            starts++
            sink.onOpen()
        }

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        override fun close() {
            closes++
        }
    }

    private class Rig {
        lateinit var socket: BareSocket
        lateinit var sink: WsChannelSink
        val mux = RemoteStreamMux { s ->
            sink = s
            BareSocket(s).also { socket = it }
        }
    }

    @Test
    fun `a mux opens a stream, delivers items and ends it over a bare MuxSocket`() = runBlocking {
        val rig = Rig()
        rig.mux.start()
        rig.mux.awaitOpen()
        assertEquals(1, rig.socket.starts)

        val stream = rig.mux.open("session/follow", JsonObject(mapOf("sessionId" to JsonPrimitive("s1"))))
        val open = rig.socket.sent.single()
        assertTrue(open, "\"type\":\"open\"" in open && "\"endpoint\":\"session/follow\"" in open)

        rig.sink.onMessage("""{"type":"item","streamId":"1","value":{"n":1}}""")
        rig.sink.onMessage("""{"type":"end","streamId":"1"}""")

        val first = stream.receive()!!.jsonObject
        assertEquals("1", (first["n"] as JsonPrimitive).content)
        assertNull(stream.receive())
    }

    @Test
    fun `closing the mux closes the socket once and fails open streams as a carrier failure`() = runBlocking {
        val rig = Rig()
        rig.mux.start()
        rig.mux.awaitOpen()
        val stream = rig.mux.open("workspace/follow")

        rig.mux.close()
        rig.mux.close()

        assertEquals(1, rig.socket.closes)
        val failure = try {
            stream.receive()
            null
        } catch (e: RemoteStreamException) {
            e
        }
        assertNotNull(failure)
        assertTrue(failure!!.carrier)
        assertTrue(rig.mux.failure is MuxClosedException)
    }

    @Test
    fun `start is idempotent and builds exactly one socket`() {
        var built = 0
        val mux = RemoteStreamMux { sink ->
            built++
            BareSocket(sink)
        }
        mux.start()
        mux.start()
        assertEquals(1, built)
    }

    @Test
    fun `a socket that refuses a send fails the open as a carrier failure`() = runBlocking {
        val mux = RemoteStreamMux { sink ->
            object : MuxSocket {
                override fun start() = sink.onOpen()
                override fun send(text: String) = false
                override fun close() = Unit
            }
        }
        mux.start()
        mux.awaitOpen()
        val failure = try {
            mux.open("session/follow")
            null
        } catch (e: RemoteStreamException) {
            e
        }
        assertTrue(failure?.carrier == true)
    }
}
