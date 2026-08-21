package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.ServerRequest
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.WsDownlink
import com.labteto.dshmobile.core.wire.WsDownlinkSink
import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.mockharness.RelayMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The credential has to be on the unary calls *and* on both WebSocket upgrades.
 *
 * The upgrade is the half that is easy to miss and expensive to miss: the connection loop opens
 * `/api/events.mux` and `/api/events.host` together and gives them 3000ms, and a relay refuses an
 * upgrade with no `Authorization` at the handshake. That surfaces as "the streams would not open",
 * which reads like a firewall or a VPN — everything except the one thing it is.
 */
class RelayAuthHeaderTest {

    private lateinit var harness: MockHarness
    private var port: Int = -1
    private val http = OkHttpClient()

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
        val result = clientWith(token).hostDescribe()
        assertTrue(result is RpcResult.Ok)
    }

    /**
     * Unauthenticated is 403, and 403 is what the client already reads as "reached it, it refused
     * me" — which is why the relay never answers 401.
     */
    @Test
    fun `an unauthenticated unary call is refused with the fence marker`() = runBlocking {
        val result = clientWith(null).hostDescribe()
        val error = (result as RpcResult.Err).error
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.of(error))
        assertEquals(403, TransportFailures.statusOf(error))
    }

    @Test
    fun `an authenticated upgrade opens`() {
        val sink = LatchSink()
        val socket = WsDownlink("$baseUrl/api/events.mux", http, sink, token)
        socket.start()
        assertTrue(sink.opened.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        socket.close()
    }

    @Test
    fun `an upgrade with no credential is refused at the handshake`() {
        val sink = LatchSink()
        val socket = WsDownlink("$baseUrl/api/events.mux", http, sink, authorization = null)
        socket.start()
        assertTrue(sink.closed.await(5, TimeUnit.SECONDS))
        val failure = sink.failure
        assertNotNull(failure)
        // Re-wrapped rather than left as OkHttp's bare ProtocolException, so a refused stream
        // classifies the same way a refused POST does.
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.classify(failure))
        socket.close()
    }

    /** Both downlinks carry it; testing only the mux one would miss half the handshake. */
    @Test
    fun `the host downlink needs the credential too`() {
        val sink = LatchSink()
        val socket = WsDownlink("$baseUrl/api/events.host", http, sink, authorization = null)
        socket.start()
        assertTrue(sink.closed.await(5, TimeUnit.SECONDS))
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.classify(sink.failure))
        socket.close()
    }

    private fun clientWith(authorization: String?) = DshApiClient(
        transport = OkHttpRpcTransport(
            baseUrl = baseUrl,
            client = http,
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            authorization = authorization,
        ),
        wsFactory = { path, sink -> WsDownlink("$baseUrl$path", http, sink, authorization) },
    )

    private class LatchSink : WsDownlinkSink {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)

        @Volatile
        var failure: Throwable? = null

        override fun onFrame(frame: ServerRequest) = Unit

        override fun onOpen() {
            opened.countDown()
        }

        override fun onClosed(cause: Throwable?) {
            failure = cause
            closed.countDown()
        }
    }
}
