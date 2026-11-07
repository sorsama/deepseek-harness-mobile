package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The loop has to say *why* a generation failed.
 *
 * The manager used to infer failure from a 2500ms timer that checked whether the phase was still
 * CONNECTING — which it never was, because the loop publishes RECONNECTING as its first act. The
 * result was a Connect button disabled forever with nothing on screen. These tests pin the
 * replacement: an explicit report per failed attempt, carrying the cause.
 *
 * Harness 0.1.2 moved where readiness comes from. There is one socket rather than two, and the
 * step that decides a generation is the opening `ready` frame of the `$events` logical stream
 * rather than a `host.describe` answer — so the failure a caller has to be able to explain is now
 * "the stream opened with the wrong thing", not "a call came back an error".
 */
class ConnectionLoopHandshakeTest {

    /** A [WsChannel] that never touches a socket; [behaviour] decides what the sink hears. */
    private class FakeChannel(
        private val sink: WsChannelSink,
        private val behaviour: FakeChannel.(WsChannelSink) -> Unit,
    ) : WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
        /** Every client message sent on this socket, in order. */
        val sent = CopyOnWriteArrayList<String>()
        var closed = false

        override fun start() = behaviour(sink)

        override fun send(text: String): Boolean {
            sent.add(text)
            onSend(this, text)
            return true
        }

        override fun close() {
            closed = true
        }

        /** Invoked after each client message, so a fake can answer an `open` with items. */
        var onSend: (FakeChannel, String) -> Unit = { _, _ -> }
    }

    private class Recorder : LoopSinks {
        val steps = CopyOnWriteArrayList<HandshakeStep>()
        val failures = CopyOnWriteArrayList<Pair<Int, GenerationFailure>>()
        val connected = CopyOnWriteArrayList<HostGeneration>()
        val frames = CopyOnWriteArrayList<RemoteEventFrame>()
        override fun onEventFrame(frame: RemoteEventFrame) {
            frames.add(frame)
        }
        override fun onConnected(generation: HostGeneration) {
            connected.add(generation)
        }
        override fun onStateChange(state: ConnectionState) = Unit
        override fun onHandshakeStep(step: HandshakeStep) {
            steps.add(step)
        }
        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            failures.add(attempt to failure)
        }
    }

    /** The stream id the loop mints for its first `open`, read back out of the message it sent. */
    private fun streamIdOf(openMessage: String): String =
        Regex("\"streamId\":\"([^\"]+)\"").find(openMessage)!!.groupValues[1]

    private fun item(streamId: String, value: String) =
        """{"type":"item","streamId":"$streamId","value":$value}"""

    private val readyFrame =
        """{"type":"ready","clientId":"client-1","host":{"home":"/home/demo"}}"""

    /** A socket that opens and answers the `$events` open with [firstItem]. */
    private fun openingWith(firstItem: String): FakeChannel.(WsChannelSink) -> Unit = { sink ->
        onSend = { channel, text ->
            if (text.contains("\"type\":\"open\"")) {
                sink.onMessage(item(streamIdOf(text), firstItem))
            }
        }
        sink.onOpen()
    }

    private fun loop(
        recorder: Recorder,
        open: FakeChannel.(WsChannelSink) -> Unit,
    ): ConnectionLoop = ConnectionLoop(
        muxFactory = { RemoteStreamMux { sink -> FakeChannel(sink, open) } },
        sinks = recorder,
        config = LoopConfig(streamOpenTimeoutMs = 30, readyTimeoutMs = 60, delay = { }),
    )

    /** Wait until [predicate] holds, so the test does not depend on loop scheduling. */
    private suspend fun await(predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(5_000) {
            while (!predicate()) kotlinx.coroutines.delay(5)
            true
        } ?: false

    @Test
    fun `a socket that never opens reports a timeout, not silence`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(recorder, open = { /* never calls onOpen */ })
        loop.start()
        assertTrue("expected a reported failure", await { recorder.failures.isNotEmpty() })
        loop.stop()

        val (attempt, failure) = recorder.failures.first()
        assertEquals(1, attempt)
        assertTrue("was $failure", failure is GenerationFailure.MuxTimedOut)
        assertEquals(30L, (failure as GenerationFailure.MuxTimedOut).timeoutMs)
    }

    @Test
    fun `a rejected upgrade reports the trust fence rather than a protocol error`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(
            recorder,
            open = { sink -> sink.onClosed(RpcTransportException(403, carrierMessage(403))) },
        )
        loop.start()
        assertTrue(await { recorder.failures.isNotEmpty() })
        loop.stop()

        val failure = recorder.failures.first().second
        assertTrue("was $failure", failure is GenerationFailure.MuxFailed)
        assertEquals(TransportFailure.TRUST_FENCE, (failure as GenerationFailure.MuxFailed).kind)
    }

    @Test
    fun `an unauthenticated upgrade is told apart from a fenced one`() = runBlocking {
        // 0.1.2 authenticates the whole `/api` surface, so an unpaired direct connection is
        // refused at this upgrade with 401 rather than on its first call. It needs a different
        // remedy from a 403, so it must not collapse into one.
        val recorder = Recorder()
        val loop = loop(
            recorder,
            open = { sink -> sink.onClosed(RpcTransportException(401, carrierMessage(401))) },
        )
        loop.start()
        assertTrue(await { recorder.failures.isNotEmpty() })
        loop.stop()

        val failure = recorder.failures.first().second as GenerationFailure.MuxFailed
        assertEquals(TransportFailure.UNAUTHENTICATED, failure.kind)
    }

    @Test
    fun `a stream that opens with something other than ready fails the generation`() = runBlocking {
        // The ready frame is not one frame among several: every later waterfall answer is bound
        // to the clientId it carries, so a generation that never saw one has nothing to answer
        // with. Treating a stray opening frame as skippable would produce a connection that
        // looks healthy and cannot reply to a single approval.
        val recorder = Recorder()
        val loop = loop(recorder, open = openingWith("""{"type":"emit","event":"x","args":[]}"""))
        loop.start()
        assertTrue(await { recorder.failures.isNotEmpty() })
        loop.stop()

        val failure = recorder.failures.first().second
        assertTrue("was $failure", failure is GenerationFailure.ReadyFailed)
        assertTrue((failure as GenerationFailure.ReadyFailed).error.message.contains("ready"))
        assertEquals(listOf(HandshakeStep.OPENING_MUX, HandshakeStep.AWAITING_READY), recorder.steps.take(2))
    }

    @Test
    fun `a stream error before ready rides along as the failure`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(
            recorder,
            open = { sink ->
                onSend = { _, text ->
                    if (text.contains("\"type\":\"open\"")) {
                        sink.onMessage(
                            """{"type":"error","streamId":"${streamIdOf(text)}",""" +
                                """"error":{"code":"forbidden","message":"nope","details":{}}}""",
                        )
                    }
                }
                sink.onOpen()
            },
        )
        loop.start()
        assertTrue(await { recorder.failures.isNotEmpty() })
        loop.stop()

        val failure = recorder.failures.first().second
        assertTrue("was $failure", failure is GenerationFailure.ReadyFailed)
        assertEquals("forbidden", (failure as GenerationFailure.ReadyFailed).error.code)
    }

    @Test
    fun `a socket that opens but never answers times out on the ready frame`() = runBlocking {
        // A distinct failure from the socket never opening: the carrier is established and the
        // host is simply not answering, which TCP will not report on its own.
        val recorder = Recorder()
        val loop = loop(recorder, open = { sink -> sink.onOpen() })
        loop.start()
        assertTrue(await { recorder.failures.isNotEmpty() })
        loop.stop()

        val failure = recorder.failures.first().second
        assertTrue("was $failure", failure is GenerationFailure.ReadyFailed)
    }

    @Test
    fun `consecutive failures increment the attempt counter`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(recorder, open = { /* never opens */ })
        loop.start()
        assertTrue(await { recorder.failures.size >= 2 })
        loop.stop()

        assertEquals(listOf(1, 2), recorder.failures.take(2).map { it.first })
    }

    @Test
    fun `the happy path announces both steps then connects on the ready frame`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(recorder, open = openingWith(readyFrame))
        loop.start()
        assertTrue(await { recorder.connected.isNotEmpty() })
        loop.stop()

        assertEquals(listOf(HandshakeStep.OPENING_MUX, HandshakeStep.AWAITING_READY), recorder.steps.take(2))
        val generation = recorder.connected.first()
        assertEquals("/home/demo", generation.description.home)
        // Retained rather than logged: it is what binds every `$events/result` reply to this
        // generation, and the host refuses one carrying a retired id.
        assertEquals("client-1", generation.clientId)
        assertTrue(recorder.failures.isEmpty())
    }

    @Test
    fun `events after the ready frame reach the sink`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(
            recorder,
            open = { sink ->
                onSend = { _, text ->
                    if (text.contains("\"type\":\"open\"")) {
                        val id = streamIdOf(text)
                        sink.onMessage(item(id, readyFrame))
                        sink.onMessage(
                            item(id, """{"type":"emit","event":"commands/change","args":[]}"""),
                        )
                    }
                }
                sink.onOpen()
            },
        )
        loop.start()
        assertTrue(await { recorder.frames.isNotEmpty() })
        loop.stop()

        // The ready frame is consumed by the handshake and must not be forwarded twice.
        val frame = recorder.frames.first()
        assertTrue("was $frame", frame is RemoteEventFrame.Emit)
        assertEquals("commands/change", (frame as RemoteEventFrame.Emit).event)
    }

    @Test
    fun `the loop opens the events stream by name`() = runBlocking {
        val sockets = CopyOnWriteArrayList<FakeChannel>()
        val recorder = Recorder()
        val loop = ConnectionLoop(
            muxFactory = {
                RemoteStreamMux { sink ->
                    FakeChannel(sink, openingWith(readyFrame)).also { sockets.add(it) }
                }
            },
            sinks = recorder,
            config = LoopConfig(streamOpenTimeoutMs = 30, readyTimeoutMs = 60, delay = { }),
        )
        loop.start()
        assertTrue(await { recorder.connected.isNotEmpty() })
        loop.stop()

        val open = sockets.first().sent.first()
        assertTrue("was $open", open.contains("\"endpoint\":\"\$events\""))
        // The standard Remote payload, even for a stream with no arguments of its own.
        assertTrue("was $open", open.contains("\"payload\":{\"args\":{}}"))
    }
}
