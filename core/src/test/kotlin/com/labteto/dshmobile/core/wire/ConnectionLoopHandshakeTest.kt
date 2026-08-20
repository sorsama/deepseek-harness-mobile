package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.HostDescription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The loop has to say *why* a generation failed.
 *
 * The manager used to infer failure from a 2500ms timer that checked whether the phase was still
 * CONNECTING — which it never was, because the loop publishes RECONNECTING as its first act. The
 * result was a Connect button disabled forever with nothing on screen. These tests pin the
 * replacement: an explicit report per failed attempt, carrying the cause.
 */
class ConnectionLoopHandshakeTest {

    /** A WsDownlink that never touches a socket; [behaviour] decides what the sink hears. */
    private class FakeWs(
        private val sink: WsDownlinkSink,
        private val behaviour: (WsDownlinkSink) -> Unit,
    ) : WsDownlink("http://stub/api/events.mux", OkHttpClient(), sink) {
        var closed = false
        override fun start() = behaviour(sink)
        override fun close() {
            closed = true
        }
    }

    private class StubTransport(private val describe: () -> RpcHttpResponse) : RpcTransport {
        override suspend fun post(path: String, body: String): RpcHttpResponse = describe()
        override suspend fun <T> download(path: String, consume: (String?, String?, InputStream) -> T): T =
            error("not used")
    }

    private class Recorder : LoopSinks {
        val steps = CopyOnWriteArrayList<HandshakeStep>()
        val failures = CopyOnWriteArrayList<Pair<Int, GenerationFailure>>()
        val connected = CopyOnWriteArrayList<HostDescription>()
        override fun onMuxFrame(frame: ServerRequest) = Unit
        override fun onHostFrame(frame: ServerRequest) = Unit
        override fun onConnected(description: HostDescription) {
            connected.add(description)
        }
        override fun onStateChange(state: ConnectionState) = Unit
        override fun onHandshakeStep(step: HandshakeStep) {
            steps.add(step)
        }
        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            failures.add(attempt to failure)
        }
    }

    private fun describeOk() = RpcHttpResponse(
        200,
        """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":""" +
            """{"version":"0.1.0-rc.8","cwd":"/tmp","attachedSessions":0,"home":"/home/demo",""" +
            """"canOpenPath":false}}}""",
    )

    /** A pre-0.1.0-rc.8 host: the same value with the field that release made required removed. */
    private fun describeRc7() = RpcHttpResponse(
        200,
        """{"type":"server-response","rpcId":"r","result":{"ok":true,"value":""" +
            """{"version":"0.1.0-rc.7","cwd":"/tmp","attachedSessions":0,"canOpenPath":false}}}""",
    )

    private fun describeErr(code: String) = RpcHttpResponse(
        200,
        """{"type":"server-response","rpcId":"r","result":{"ok":false,"error":""" +
            """{"code":"$code","message":"nope","details":{}}}}""",
    )

    private fun loop(
        recorder: Recorder,
        open: (WsDownlinkSink) -> Unit,
        describe: () -> RpcHttpResponse = ::describeOk,
    ): ConnectionLoop {
        val api = DshApiClient(
            transport = StubTransport(describe),
            wsFactory = { _, sink -> FakeWs(sink, open) },
        )
        return ConnectionLoop(
            api = api,
            sinks = recorder,
            config = LoopConfig(streamOpenTimeoutMs = 30, delay = { }),
        )
    }

    /** Wait until [predicate] holds, so the test does not depend on loop scheduling. */
    private suspend fun await(predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(5_000) {
            while (!predicate()) kotlinx.coroutines.delay(5)
            true
        } ?: false

    @Test
    fun `streams that never open report a timeout, not silence`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(recorder, open = { /* never calls onOpen */ })
        loop.start()
        assertTrue("expected a reported failure", await { recorder.failures.isNotEmpty() })
        loop.stop()

        val (attempt, failure) = recorder.failures.first()
        assertEquals(1, attempt)
        assertTrue("was $failure", failure is GenerationFailure.StreamsTimedOut)
        assertEquals(30L, (failure as GenerationFailure.StreamsTimedOut).timeoutMs)
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
        assertTrue("was $failure", failure is GenerationFailure.StreamFailed)
        assertEquals(TransportFailure.TRUST_FENCE, (failure as GenerationFailure.StreamFailed).kind)
    }

    @Test
    fun `streams open but describe fails, and the error rides along`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(recorder, open = { it.onOpen() }, describe = { describeErr("forbidden") })
        loop.start()
        assertTrue(await { recorder.failures.isNotEmpty() })
        loop.stop()

        val failure = recorder.failures.first().second
        assertTrue("was $failure", failure is GenerationFailure.DescribeFailed)
        assertEquals("forbidden", (failure as GenerationFailure.DescribeFailed).error.code)
        // Both steps were announced before it failed, so the UI could name where it stopped.
        assertEquals(listOf(HandshakeStep.OPENING_STREAMS, HandshakeStep.DESCRIBING), recorder.steps.take(2))
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
    fun `the happy path announces both steps then connects`() = runBlocking {
        val recorder = Recorder()
        val loop = loop(recorder, open = { it.onOpen() })
        loop.start()
        assertTrue(await { recorder.connected.isNotEmpty() })
        loop.stop()

        assertEquals(listOf(HandshakeStep.OPENING_STREAMS, HandshakeStep.DESCRIBING), recorder.steps.take(2))
        assertEquals("0.1.0-rc.8", recorder.connected.first().version)
        assertEquals("/home/demo", recorder.connected.first().home)
        assertTrue(recorder.failures.isEmpty())
    }

    @Test
    fun `a host predating the home field still connects`() = runBlocking {
        // `home` became required in 0.1.0-rc.8. Declaring it non-null on this side would have
        // turned every older harness into "not a harness" at the one step that decides whether
        // the app connects at all, so its absence has to stay a fact rather than a failure.
        val recorder = Recorder()
        val loop = loop(recorder, open = { it.onOpen() }, describe = ::describeRc7)
        loop.start()
        assertTrue(await { recorder.connected.isNotEmpty() })
        loop.stop()

        assertEquals("0.1.0-rc.7", recorder.connected.first().version)
        assertNull(recorder.connected.first().home)
        assertTrue(recorder.failures.isEmpty())
    }

    @Test
    fun `the describe answer decides which command shape this connection sends`() = runBlocking {
        // The one place the client has to choose what to *send* rather than what to ignore:
        // `commands/execute` gained a required `images` argument in 0.1.0-rc.8 and the gateway
        // refuses an args object that does not match its descriptor in either direction.
        val rc8 = DshApiClient(StubTransport(::describeOk)) { _, sink -> FakeWs(sink) { } }
        assertFalse("undecided until the host answers", rc8.acceptsCommandImages)
        assertTrue(rc8.hostDescribe() is RpcResult.Ok)
        assertTrue(rc8.acceptsCommandImages)

        val rc7 = DshApiClient(StubTransport(::describeRc7)) { _, sink -> FakeWs(sink) { } }
        assertTrue(rc7.hostDescribe() is RpcResult.Ok)
        assertFalse(rc7.acceptsCommandImages)
    }
}
