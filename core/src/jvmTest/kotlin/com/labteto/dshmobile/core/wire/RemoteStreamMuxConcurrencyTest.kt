package com.labteto.dshmobile.core.wire

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mux is called from the socket's thread and from coroutines at once, and the guarantees that
 * keep that safe are the ones documented on [RemoteStreamMux]. They moved from `java.util.concurrent`
 * types to atomicfu and [AtomicRegistry] with the class, so each one is raced here on real threads
 * rather than assumed to have survived the move.
 */
class RemoteStreamMuxConcurrencyTest {

    private class QuietSocket(private val sink: WsChannelSink) : MuxSocket {
        val sent = CopyOnWriteArrayList<String>()
        override fun start() = sink.onOpen()
        override fun send(text: String): Boolean {
            sent += text
            return true
        }
        override fun close() = Unit
    }

    private class Rig {
        lateinit var sink: WsChannelSink
        lateinit var socket: QuietSocket
        val mux = RemoteStreamMux { s ->
            sink = s
            QuietSocket(s).also { socket = it }
        }

        suspend fun ready(): Rig = apply {
            mux.start()
            mux.awaitOpen()
        }
    }

    /** What a consumer sees on its first receive: the terminal kind, or null if it hangs. */
    private suspend fun terminalOf(stream: RemoteStream, timeoutMs: Long = 2_000): String? =
        withTimeoutOrNull(timeoutMs) {
            try {
                if (stream.receive() == null) "ended" else "item"
            } catch (e: RemoteStreamException) {
                if (e.carrier) "carrier-failure" else "business-failure"
            }
        }

    @Test
    fun `a stream opened while the mux closes is failed or refused, never left hanging`() = runBlocking {
        repeat(150) {
            val rig = Rig().ready()
            val opened = CopyOnWriteArrayList<RemoteStream>()
            val refused = AtomicInteger()
            val gate = CountDownLatch(1)
            val opener = thread {
                gate.await()
                repeat(40) {
                    try {
                        opened += rig.mux.open("session/follow")
                    } catch (e: RemoteStreamException) {
                        assertTrue("a refused open is a carrier failure", e.carrier)
                        refused.incrementAndGet()
                    }
                }
            }
            val closer = thread {
                gate.await()
                rig.mux.close()
            }
            gate.countDown()
            opener.join()
            closer.join()

            for (stream in opened) {
                assertEquals("carrier-failure", terminalOf(stream))
            }
            assertEquals(40, opened.size + refused.get())
        }
    }

    @Test
    fun `a stream the host ends while the mux closes is always settled`() = runBlocking {
        repeat(60) {
            val rig = Rig().ready()
            val streams = List(8) { rig.mux.open("session/follow") }
            val gate = CountDownLatch(1)
            val ender = thread {
                gate.await()
                for (id in 1..8) rig.sink.onMessage("""{"type":"end","streamId":"$id"}""")
            }
            val closer = thread {
                gate.await()
                rig.mux.close()
            }
            gate.countDown()
            ender.join()
            closer.join()

            // Whichever side wins, the consumer's first read is a terminal and never a hang. That
            // each stream gets *only one* is the next test's business.
            for (stream in streams) {
                val first = terminalOf(stream)
                assertNotNull("every stream is settled one way or the other", first)
                assertTrue(first, first == "ended" || first == "carrier-failure")
            }
        }
    }

    /**
     * A host `end` or `error` and the failure sweep both end a stream, and only the one that
     * actually retires it may signal it.
     *
     * The race is a window of a few nanoseconds inside one `onMessage`, so a single run proves
     * nothing: the sweep is released at a different point of a long run of host terminals each round,
     * and every stream is then asked for a *second* terminal, which is queued (and returned at once)
     * only if both sides signalled it. Before the host side let the remove decide, this failed on
     * most runs of the whole test.
     */
    @Test
    fun `a host terminal racing the sweep signals each stream exactly once`() = runBlocking {
        val streamCount = 400
        repeat(150) { round ->
            val rig = Rig().ready()
            val streams = List(streamCount) { rig.mux.open("session/follow") }
            val delivered = AtomicInteger()
            val sweepAt = Random(round).nextInt(streamCount / 4, streamCount)
            val gate = CountDownLatch(1)
            val host = thread {
                gate.await()
                for (id in 1..streamCount) {
                    rig.sink.onMessage(
                        if (id % 2 == 0) {
                            """{"type":"end","streamId":"$id"}"""
                        } else {
                            """{"type":"error","streamId":"$id","error":{"code":"x","message":"m","details":{}}}"""
                        },
                    )
                    delivered.incrementAndGet()
                }
            }
            val sweeper = thread {
                gate.await()
                while (delivered.get() < sweepAt) Thread.onSpinWait()
                rig.mux.close()
            }
            gate.countDown()
            host.join()
            sweeper.join()

            for (stream in streams) assertNotNull("every stream is settled", terminalOf(stream))
            // All the streams' second reads at once: a queued signal answers without suspending, so
            // the 1 ms bound only ever expires for a stream that has nothing more.
            val extra = coroutineScope {
                streams.map { async { terminalOf(it, timeoutMs = 1) } }.awaitAll()
            }
            assertEquals("a stream was signalled twice", 0, extra.count { it != null })
        }
    }

    @Test
    fun `cancels racing a close never cost a kept stream its failure`() = runBlocking {
        repeat(60) {
            val rig = Rig().ready()
            val cancelled = List(8) { rig.mux.open("session/follow") }
            val kept = List(8) { rig.mux.open("session/follow") }
            val gate = CountDownLatch(1)
            val canceller = thread {
                gate.await()
                cancelled.forEach { it.cancel() }
            }
            val closer = thread {
                gate.await()
                rig.mux.close()
            }
            gate.countDown()
            canceller.join()
            closer.join()

            // A stream that was not cancelled is always failed by the close.
            for (stream in kept) assertEquals("carrier-failure", terminalOf(stream))
        }
    }

    @Test
    fun `stream ids stay unique when many threads open at once`() = runBlocking {
        val rig = Rig().ready()
        val gate = CountDownLatch(1)
        val workers = List(8) {
            thread {
                gate.await()
                repeat(100) { rig.mux.open("session/follow") }
            }
        }
        gate.countDown()
        workers.forEach { it.join() }

        val ids = rig.socket.sent.map { Regex("\"streamId\":\"([^\"]+)\"").find(it)!!.groupValues[1] }
        assertEquals(800, ids.size)
        assertEquals("no id was handed out twice", 800, ids.toSet().size)
    }

    @Test
    fun `racing starts build exactly one socket`() = runBlocking {
        val built = AtomicInteger()
        val mux = RemoteStreamMux { sink ->
            built.incrementAndGet()
            QuietSocket(sink)
        }
        val gate = CountDownLatch(1)
        val workers = List(16) {
            thread {
                gate.await()
                mux.start()
            }
        }
        gate.countDown()
        workers.forEach { it.join() }
        assertEquals(1, built.get())
    }

    @Test
    fun `cancel is idempotent under racing callers and sends one cancel`() = runBlocking {
        val rig = Rig().ready()
        val stream = rig.mux.open("session/follow")
        val gate = CountDownLatch(1)
        val workers = List(8) {
            thread {
                gate.await()
                stream.cancel()
            }
        }
        gate.countDown()
        workers.forEach { it.join() }

        val cancels = rig.socket.sent.count { "\"type\":\"cancel\"" in it }
        assertEquals(1, cancels)
    }
}
