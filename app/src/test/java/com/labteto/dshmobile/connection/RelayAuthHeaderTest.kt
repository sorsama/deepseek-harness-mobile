package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.WsChannelSink
import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.mockharness.RelayMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The credential has to be on the unary calls *and* on the WebSocket upgrade.
 *
 * The upgrade is the half that is easy to miss and expensive to miss: the connection loop opens
 * `/api/remote.mux` and gives it 3000ms, and a relay refuses an upgrade with no `Authorization`
 * at the handshake. That surfaces as "the stream would not open", which reads like a firewall or
 * a VPN — everything except the one thing it is.
 */
@RunWith(Parameterized::class)
class RelayAuthHeaderTest(private val kind: TransportKind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<TransportKind> = TransportKind.parameters()
    }

    private lateinit var harness: MockHarness
    private var port: Int = -1

    private val token = "Bearer relay-test-token"
    private val baseUrl get() = "http://127.0.0.1:$port"

    @Before
    fun setUp() = runBlocking {
        harness = MockHarness(port = 0, relay = RelayMode())
        port = harness.start()
    }

    @After
    fun tearDown() = runBlocking { harness.stop() }

    @Test
    fun `an authenticated unary call reaches the harness`() = runBlocking {
        val result = clientWith(token).sessionCanOpenWorkspacePath()
        assertTrue(result is RpcResult.Ok)
    }

    /**
     * Unauthenticated is 403, and 403 is what the client already reads as "reached it, it refused
     * me" — which is why the relay never answers 401.
     */
    @Test
    fun `an unauthenticated unary call is refused with the fence marker`() = runBlocking {
        val result = clientWith(null).sessionCanOpenWorkspacePath()
        val error = (result as RpcResult.Err).error
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.of(error))
        assertEquals(403, TransportFailures.statusOf(error))
    }

    @Test
    fun `an authenticated upgrade opens`() {
        val sink = LatchSink()
        val socket = kind.socket("$baseUrl/api/remote.mux", sink, token)
        socket.start()
        assertTrue(sink.opened.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        socket.close()
    }

    @Test
    fun `an upgrade with no credential is refused at the handshake`() {
        val sink = LatchSink()
        val socket = kind.socket("$baseUrl/api/remote.mux", sink, authorization = null)
        socket.start()
        assertTrue(sink.closed.await(5, TimeUnit.SECONDS))
        val failure = sink.failure
        assertNotNull(failure)
        // Re-wrapped rather than left as OkHttp's bare ProtocolException, so a refused stream
        // classifies the same way a refused POST does.
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.classify(failure))
        socket.close()
    }

    /**
     * The mux is written to, so the credential has to survive past the handshake as well.
     *
     * Its predecessors were downlink-only and there were two of them; this is the one socket, and
     * a client that cannot send on it cannot open a single stream.
     */
    @Test
    fun `an authenticated socket accepts a stream open`() {
        val sink = LatchSink()
        val socket = kind.socket("$baseUrl/api/remote.mux", sink, token)
        socket.start()
        assertTrue(sink.opened.await(5, TimeUnit.SECONDS))
        assertTrue(
            socket.send(
                """{"type":"open","streamId":"1","endpoint":"${'$'}events","payload":{"args":{}}}""",
            ),
        )
        socket.close()
    }

    private fun clientWith(authorization: String?) = DshApiClient(
        transport = kind.rpc(
            baseUrl = baseUrl,
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            authorization = authorization,
        ),
    )

    private class LatchSink : WsChannelSink {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)

        @Volatile
        var failure: Throwable? = null

        override fun onMessage(text: String) = Unit

        override fun onOpen() {
            opened.countDown()
        }

        override fun onClosed(cause: Throwable?) {
            failure = cause
            closed.countDown()
        }
    }
}
