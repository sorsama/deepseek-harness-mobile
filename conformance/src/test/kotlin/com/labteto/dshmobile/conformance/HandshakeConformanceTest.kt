package com.labteto.dshmobile.conformance

import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import com.labteto.dshmobile.core.wire.dto.REMOTE_EVENT_STREAM_ENDPOINT
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Connecting to a real harness, and the two refusals that decide what the app tells the person.
 *
 * These are the cases `:mock-harness` cannot model at all. It implements no `GET /?token=` route,
 * issues no cookie and has no 401 — it only knows the relay's bearer scheme, which answers 403 — so
 * the authentication tier that every directly-connected user is on has never been checked against
 * anything but this client's own idea of it. Since harness 0.1.2 that tier is the whole `/api`
 * surface.
 *
 * The 401/403 split is the part worth pinning. They need opposite remedies: a 403 is about where
 * the request came from and is fixed on the harness, a 401 is about who is asking and is fixed by
 * exchanging a launch token. Collapsing them sends people to reconfigure a firewall when they
 * actually need to re-pair, which is exactly what the connect screen's copy turns on.
 */
class HandshakeConformanceTest {

    private lateinit var harness: HarnessProcess
    private lateinit var client: HarnessClient

    @Before
    fun boot() {
        assumeTrue("no built harness checkout; see HarnessProcess", HarnessProcess.available())
        harness = HarnessProcess.start()
        client = HarnessClient(harness)
    }

    @After
    fun stop() {
        if (this::client.isInitialized) client.close()
        if (this::harness.isInitialized) harness.close()
    }

    /**
     * The launch token really does buy a browser session, and the session really does open `/api`.
     *
     * `HarnessClient` throws if the exchange is refused, so reaching this assertion is already the
     * substance; the call afterwards proves the cookie is accepted rather than merely issued.
     */
    @Test
    fun `a launch token opens the api`() = runBlocking {
        assertTrue("the cookie should be a dsh-auth session", client.cookie.startsWith("dsh-auth-"))
        when (val result = client.api.sessionList()) {
            is RpcResult.Ok -> Unit
            is RpcResult.Err -> error("authenticated session/list failed: ${result.error}")
        }
    }

    /**
     * The run drives the transport it was asked to: `-Pdsh.transport=ktor` has to mean Ktor for the
     * unary calls and for the mux, or the second CI pass would be the first one run twice.
     */
    @Test
    fun `the run uses the transport it was asked for`() = runBlocking {
        val ktor = System.getProperty("dsh.transport")?.trim()?.lowercase() == "ktor"
        assertEquals(if (ktor) "KtorRpcTransport" else "OkHttpRpcTransport", client.rpcTransportClass)
        client.mux.start()
        withTimeout(15_000) { client.mux.awaitOpen() }
        assertEquals(if (ktor) "KtorMuxSocket" else "WsChannel", client.muxSocketClass)
    }

    /** Without a session the harness answers 401, which the client reads as a missing session. */
    @Test
    fun `an unauthenticated call is unauthenticated, not a broken connection`() = runBlocking {
        when (val result = client.anonymous().sessionList()) {
            is RpcResult.Ok -> error("the harness answered an unauthenticated call")
            is RpcResult.Err -> {
                assertEquals("unauthenticated", result.error.code)
                assertEquals(TransportFailure.UNAUTHENTICATED, TransportFailures.of(result.error))
                assertEquals(401, TransportFailures.statusOf(result.error))
            }
        }
    }

    /*
     * The 403 half of the pair is deliberately not tested here.
     *
     * The fence trusts any loopback authority, and `dsh web` refuses to bind anything but loopback
     * (`--host 0.0.0.0` is a hard usage error upstream, on purpose). So every authority that can
     * reach this harness is one the fence accepts, and provoking a 403 would take a hostname that
     * resolves to loopback without being spelled like it — a DNS dependency this suite should not
     * take on. The 403 path keeps its coverage where it does not need a live harness:
     * `HostHeaderTest` pins the carrier wording, `ConnectDiagnosisTest` pins what the connect
     * screen says, and `:mock-harness` serves the fence itself.
     */

    /**
     * The mux opens and `$events` yields `ready` first, carrying the two facts the rest depends on.
     *
     * `clientId` binds every later answer to this connection generation — without it no approval or
     * question can be settled — and `host.home` is the one host fact the app still has to show,
     * since 0.1.2 removed `host.describe` and publishes no version anywhere.
     */
    @Test
    fun `the events stream opens with a ready frame carrying a client id and the host home`() =
        runBlocking {
            client.mux.start()
            withTimeout(HANDSHAKE_BUDGET_MS) { client.mux.awaitOpen() }
            val stream = client.mux.open(REMOTE_EVENT_STREAM_ENDPOINT, JsonObject(emptyMap()))
            try {
                val first = withTimeout(READY_BUDGET_MS) { stream.receive() }
                    ?: error("the events stream ended before it was ready")
                val frame = WireJson.decodeFromJsonElement(RemoteEventFrame.serializer(), first)
                assertTrue("expected a ready frame, got $frame", frame is RemoteEventFrame.Ready)
                frame as RemoteEventFrame.Ready
                assertTrue("ready carried no clientId", frame.clientId.isNotBlank())
                assertTrue("ready carried no host home", frame.host.home.isNotBlank())
            } finally {
                stream.cancel()
            }
        }

    private companion object {
        /** The app's own socket budget, so a regression in either shows up here. */
        const val HANDSHAKE_BUDGET_MS = 3_000L

        /** The app's own readiness budget. */
        const val READY_BUDGET_MS = 5_000L
    }
}
