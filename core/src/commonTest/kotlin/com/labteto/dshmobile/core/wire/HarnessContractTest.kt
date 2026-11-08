package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The transport contract, asserted once against whatever carrier the platform ships: Ktor on CIO on
 * the JVM, Ktor on `NSURLSession` on iOS. The fixtures (`CiFixtures` in `:mock-harness`) answer the
 * same way to both, so a test that passes on one and fails on the other has found a behaviour of the
 * HTTP stack and not of the test. See [ContractRig] for how each platform reaches them.
 *
 * What is covered is what the harness and `dsh-relay` can observe of a client (the headers on the
 * wire, the body's encoding and length) and what a caller can observe of failures (the status table,
 * deadlines, cancellation, refused connections), for the unary transport, the streaming download and
 * upload, the plain carrier of the launch-token exchange, and the mux socket.
 */
class HarnessContractTest {

    private val rig: ContractRig get() = sharedRig

    private fun test(timeoutMs: Long = 90_000, body: suspend CoroutineScope.(HttpClient) -> Unit) = runBlocking {
        val client = rig.newClient()
        try {
            withTimeout(timeoutMs) { body(client) }
        } finally {
            client.close()
        }
    }

    private fun HttpClient.rpc(
        baseUrl: String,
        connectTimeoutMs: Long = 10_000,
        readTimeoutMs: Long = 10_000,
        authorization: String? = null,
        cookie: String? = null,
    ) = KtorRpcTransport(baseUrl, this, connectTimeoutMs, readTimeoutMs, authorization, cookie)

    // ------------------------------------------------------------------ unary calls

    @Test
    fun aHarnessAnswersTheReachabilityProbe() = test { client ->
        val api = DshApiClient(client.rpc(rig.plainUrl))
        assertEquals(RpcResult.Ok(true), api.sessionCanOpenWorkspacePath())
    }

    @Test
    fun aBodyWithNonAsciiTextSurvivesTheWire() = test { client ->
        val api = DshApiClient(client.rpc(rig.plainUrl))
        val args = buildJsonObject {
            put("text", ContractFixtures.STREAM_UNICODE)
            put("number", 42)
        }
        val result = api.call("ci/echo", args, JsonElement.serializer())
        assertEquals(RpcResult.Ok<JsonElement>(args), result)
    }

    @Test
    fun aBusinessRefusalIsNotACarrierFailure() = test { client ->
        val api = DshApiClient(client.rpc(rig.plainUrl))
        val result = api.call("ci/refuse", JsonObject(emptyMap()), JsonElement.serializer())
        val error = (result as? RpcResult.Err)?.error ?: fail("expected a refusal, got $result")
        assertEquals("ci/refused", error.code)
        assertNull(TransportFailures.of(error), "a harness's own refusal must not carry a transport verdict")
    }

    @Test
    fun aWebPageWhereAnEnvelopeShouldBeIsNotAHarness() = test { client ->
        val api = DshApiClient(client.rpc(rig.plainUrl))
        val result = api.call("ci/html", JsonObject(emptyMap()), JsonElement.serializer())
        val error = (result as? RpcResult.Err)?.error ?: fail("expected a failure, got $result")
        assertEquals(TransportFailure.NOT_A_HARNESS, TransportFailures.of(error))
    }

    @Test
    fun aPostSpellsTheHeadersTheFenceAndTheRelayRead() = test { client ->
        val body = """{"hello":"wörld"}"""
        val response = client.rpc(rig.plainUrl, authorization = "Bearer some-token", cookie = ContractFixtures.SESSION_COOKIE)
            .post("/ci/echo-request", body)
        assertEquals(200, response.status)
        val echo = Json.parseToJsonElement(response.body).jsonObject
        assertEquals("POST", echo.text("method"))
        assertEquals("/ci/echo-request", echo.text("target"))
        assertEquals(hostOf(rig.plainUrl), echo.header("host"), "Host")
        assertEquals("Bearer some-token", echo.header("authorization"), "Authorization")
        assertEquals(ContractFixtures.SESSION_COOKIE, echo.header("cookie"), "Cookie")
        assertEquals("application/json; charset=utf-8", echo.header("content-type")?.lowercase(), "Content-Type")
        assertEquals(body.encodeToByteArray().size.toString(), echo.header("content-length"), "Content-Length")
        assertEquals(body, echo.text("body"))
    }

    @Test
    fun noCredentialMeansNoCredentialHeaderAtAll() = test { client ->
        val echo = Json.parseToJsonElement(client.rpc(rig.plainUrl).post("/ci/echo-request", "{}").body).jsonObject
        assertNull(echo.header("authorization"), "Authorization")
        assertNull(echo.header("cookie"), "Cookie")
    }

    @Test
    fun aRelayWantsItsBearerTokenAndSeesIt() = test { client ->
        val refused = assertFailsWith<RpcTransportException> { client.rpc(rig.relayUrl).post("/ci/echo-request", "{}") }
        assertEquals(403, refused.status)
        assertTrue("forbidden" in refused.message.orEmpty(), "the relay's reason should be in the message: ${refused.message}")

        val bearer = "Bearer ${ContractFixtures.RELAY_TOKEN}"
        val echo = Json.parseToJsonElement(client.rpc(rig.relayUrl, authorization = bearer).post("/ci/echo-request", "{}").body).jsonObject
        assertEquals(bearer, echo.header("authorization"))
    }

    @Test
    fun theStatusTableIsTheSameOnEveryCarrier() = test { client ->
        val transport = client.rpc(rig.plainUrl)
        val expected = mapOf(
            401 to TransportFailure.UNAUTHENTICATED,
            403 to TransportFailure.TRUST_FENCE,
            404 to TransportFailure.NOT_FOUND,
            413 to TransportFailure.TOO_LARGE,
            429 to TransportFailure.RATE_LIMITED,
            500 to TransportFailure.NOT_A_HARNESS,
            502 to TransportFailure.UPSTREAM_DOWN,
        )
        for ((status, verdict) in expected) {
            val failure = assertFailsWith<RpcTransportException>("HTTP $status") { transport.post("/ci/status/$status", "{}") }
            assertEquals(status, failure.status)
            assertEquals(verdict, TransportFailures.classify(failure), "HTTP $status")
            if (status == 429) assertEquals(7L, failure.retryAfterSeconds, "Retry-After")
        }
    }

    @Test
    fun aRedirectIsNotFollowed() = test { client ->
        val failure = assertFailsWith<RpcTransportException> { client.rpc(rig.plainUrl).post("/ci/redirect", "{}") }
        assertEquals(302, failure.status)
    }

    @Test
    fun aMultipartAnswerComesBackAsRawBytes() = test { client ->
        val request = """{"type":"client-request","rpcId":"r1","method":"ci/blob","payload":{"args":{}}}"""
        val response = client.rpc(rig.plainUrl).post("/api/ci/blob", request)
        assertEquals(200, response.status)
        assertEquals("", response.body)
        assertTrue(response.contentType.orEmpty().startsWith("multipart/form-data"), "Content-Type was ${response.contentType}")
        val decoded = RpcMultipart.decode(response.contentType.orEmpty(), assertNotNull(response.multipart, "multipart bytes"))
        assertContentEquals(ContractFixtures.blobBytes(), decoded.attachments[listOf("data")])
    }

    @Test
    fun parallelCallsDoNotCrossTheirAnswers() = test { client ->
        val api = DshApiClient(client.rpc(rig.plainUrl))
        val results = coroutineScope {
            (1..12).map { n ->
                async {
                    val args = buildJsonObject { put("n", n); put("text", "call $n ${ContractFixtures.STREAM_UNICODE}") }
                    n to api.call("ci/echo", args, JsonElement.serializer())
                }
            }.awaitAll()
        }
        for ((n, result) in results) {
            val value = (result as? RpcResult.Ok)?.value?.jsonObject ?: fail("call $n failed: $result")
            assertEquals(n, value.getValue("n").jsonPrimitive.contentOrNull?.toInt())
            assertEquals("call $n ${ContractFixtures.STREAM_UNICODE}", value.text("text"))
        }
    }

    // ------------------------------------------------------------------ failures

    @Test
    fun aClosedPortIsRefused() = test { client ->
        val result = DshApiClient(client.rpc(rig.deadUrl)).sessionCanOpenWorkspacePath()
        val error = (result as? RpcResult.Err)?.error ?: fail("expected a failure, got $result")
        assertEquals(TransportFailure.REFUSED, TransportFailures.of(error), error.message)
    }

    @Test
    fun aNameThatDoesNotResolveIsADnsFailure() = test { client ->
        val result = DshApiClient(client.rpc("http://no-such-host.invalid:3181")).sessionCanOpenWorkspacePath()
        val error = (result as? RpcResult.Err)?.error ?: fail("expected a failure, got $result")
        assertEquals(TransportFailure.DNS, TransportFailures.of(error), error.message)
    }

    @Test
    fun aHarnessThatNeverAnswersTimesOut() = test { client ->
        val transport = client.rpc(rig.plainUrl, connectTimeoutMs = 5_000, readTimeoutMs = 1_500)
        val failure = try {
            transport.post("/ci/hang", "{}")
            fail("a request the server never answers must not succeed")
        } catch (e: IOException) {
            e
        }
        assertEquals(TransportFailure.TIMEOUT, TransportFailures.classify(failure), failure.toString())
    }

    @Test
    fun cancellingACallReturnsPromptlyAndLeavesTheClientUsable() = test { client ->
        val transport = client.rpc(rig.plainUrl, readTimeoutMs = 60_000)
        val inFlight = launch { transport.post("/ci/hang", "{}") }
        delay(400)
        withTimeout(5_000) { inFlight.cancelAndJoin() }
        assertTrue(inFlight.isCancelled)
        assertEquals(200, transport.post("/ci/echo-request", "{}").status)
    }

    // ------------------------------------------------------------------ downloads and uploads

    @Test
    fun aDownloadStreamsTheBytesAndTheHeaders() = test { client ->
        val transport = client.rpc(rig.plainUrl)
        var contentType: String? = null
        var disposition: String? = null
        val bytes = transport.download("/api/session.export?sessionId=s1") { type, disposal, body ->
            contentType = type
            disposition = disposal
            body.readByteArray()
        }
        assertContentEquals(ContractFixtures.exportBytes(), bytes)
        assertTrue(contentType.orEmpty().startsWith("application/zip"), "Content-Type was $contentType")
        assertEquals("attachment; filename=\"dsh-session-s1.zip\"", disposition)
    }

    @Test
    fun aConsumersOwnExceptionPassesThroughUnwrapped() = test { client ->
        val failure = assertFailsWith<IllegalStateException> {
            client.rpc(rig.plainUrl).download<Int>("/api/session.export?sessionId=s1") { _, _, _ -> error("mine") }
        }
        assertEquals("mine", failure.message)
    }

    @Test
    fun aDownloadThatIsRefusedIsAnException() = test { client ->
        val failure = assertFailsWith<RpcTransportException> {
            client.rpc(rig.plainUrl).download<ByteArray>("/api/session.export") { _, _, body -> body.readByteArray() }
        }
        assertEquals(400, failure.status)
    }

    @Test
    fun anUploadStreamsTheBodyAndReportsProgress() = test { client ->
        val data = ByteArray(200 * 1024 + 5) { ((it * 13 + 1) and 0xFF).toByte() }
        val progress = mutableListOf<Long>()
        val response = client.rpc(rig.plainUrl).upload(
            "/api/session/uploadFileBinary?sessionId=s1&name=a.bin",
            "application/octet-stream",
            data.size.toLong(),
            Buffer().apply { write(data) },
        ) { progress += it }
        assertEquals(200, response.status)
        val answer = Json.parseToJsonElement(response.body).jsonObject
        assertEquals("true", answer.getValue("ok").jsonPrimitive.content, response.body)
        val file = answer.getValue("value").jsonObject.getValue("file").jsonObject
        assertEquals(data.size.toString(), file.text("bytes"))
        assertEquals("a.bin", file.text("name"))
        // The harness names the file by the digest of the bytes it received: one flipped or dropped
        // byte on the way changes it.
        assertEquals("sha256:" + sha256(data).toHex(), file.text("attachmentId"))
        assertTrue(progress.isNotEmpty(), "progress was never reported")
        assertEquals(data.size.toLong(), progress.last(), "the last report is the total")
        assertEquals(progress.sorted(), progress, "progress never goes backwards")
    }

    // ------------------------------------------------------------------ the plain carrier

    @Test
    fun aPlainRequestReadsAStatusAndHeadersWhateverTheStatus() = test { client ->
        val http = KtorPlainHttp(client)
        val limited = http.execute(plainGet("${rig.plainUrl}/ci/status/429"))
        assertEquals(429, limited.status)
        assertEquals(listOf("7"), limited.headers("Retry-After"))
        assertEquals("status 429", limited.body)
    }

    @Test
    fun aPlainRequestDoesNotFollowARedirect() = test { client ->
        val answer = KtorPlainHttp(client).execute(plainGet("${rig.plainUrl}/ci/redirect"))
        assertEquals(302, answer.status)
        assertEquals(listOf("/ci/echo-request"), answer.headers("Location"))
    }

    @Test
    fun aPlainPostSendsItsBodyAsUtf8() = test { client ->
        val body = """{"code":"wörld"}"""
        val answer = KtorPlainHttp(client).execute(
            PlainHttpRequest(
                method = "POST",
                url = "${rig.plainUrl}/ci/echo-request",
                body = body,
                bodyContentType = "application/json; charset=utf-8",
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000,
            ),
        )
        assertEquals(200, answer.status)
        val echo = Json.parseToJsonElement(answer.body).jsonObject
        assertEquals(body.encodeToByteArray().size.toString(), echo.text("bodyLength"))
        assertEquals(body, echo.text("body"))
        assertEquals("application/json; charset=utf-8", echo.header("content-type")?.lowercase())
        assertEquals(hostOf(rig.plainUrl), echo.header("host"))
    }

    @Test
    fun repeatedSetCookieHeadersAreAllThere() = test { client ->
        val answer = KtorPlainHttp(client).execute(plainGet("${rig.plainUrl}/ci/two-cookies"))
        assertEquals(200, answer.status)
        // One header per cookie on the JVM; Darwin folds them into a single value joined by ", ".
        // Either way both cookies must be in what the caller can read.
        val joined = answer.headers("Set-Cookie").joinToString(", ")
        assertTrue("a=1" in joined && "b=2" in joined, "Set-Cookie was ${answer.headers("Set-Cookie")}")
    }

    @Test
    fun aPlainRequestToAClosedPortThrowsARefusal() = test { client ->
        val failure = try {
            KtorPlainHttp(client).execute(plainGet("${rig.deadUrl}/"))
            fail("a closed port must not answer")
        } catch (e: IOException) {
            e
        }
        assertEquals(TransportFailure.REFUSED, TransportFailures.classify(failure), failure.toString())
    }

    // ------------------------------------------------------------------ the launch-token exchange

    @Test
    fun aHarnessWithASessionAnswers401UntilTheTokenIsExchanged() = test { client ->
        val api = DshApiClient(client.rpc(rig.sessionUrl))
        val error = (api.sessionCanOpenWorkspacePath() as? RpcResult.Err)?.error ?: fail("expected 401")
        assertEquals(TransportFailure.UNAUTHENTICATED, TransportFailures.of(error), error.message)
    }

    @Test
    fun theExchangeReadsTheCookieOffTheUnfollowed303() = test { client ->
        val http = KtorPlainHttp(client)
        val granted = HarnessSession.exchange(rig.sessionUrl, ContractFixtures.SESSION_TOKEN, http)
        assertEquals(SessionExchange.Granted(ContractFixtures.SESSION_COOKIE), granted)
        val refused = HarnessSession.exchange(rig.sessionUrl, "not-the-token", http)
        assertEquals(SessionExchange.Refused(401), refused)
    }

    @Test
    fun theCookieSignsTheUnaryCallsIn() = test { client ->
        val api = DshApiClient(client.rpc(rig.sessionUrl, cookie = ContractFixtures.SESSION_COOKIE))
        assertEquals(RpcResult.Ok(true), api.sessionCanOpenWorkspacePath())
    }

    // ------------------------------------------------------------------ the mux socket

    @Test
    fun theMuxHandshakeReachesReady() = test { client ->
        val ready = assertReady(handshake(client, "${rig.plainUrl}/api/remote.mux"))
        assertEquals(ContractFixtures.HOME, ready.home)
        assertEquals(ContractFixtures.CLIENT_ID, ready.clientId)
    }

    @Test
    fun theMuxUpgradeNeedsTheSessionCookieAndSendsIt() = test { client ->
        val url = "${rig.sessionUrl}/api/remote.mux"
        val without = assertFailed(handshake(client, url))
        if (rig.muxUnauthorizedIsReported) {
            assertEquals(TransportFailure.UNAUTHENTICATED, (without as? GenerationFailure.MuxFailed)?.kind, without.toString())
        } else {
            // Darwin never completes the refused upgrade, so the handshake runs into its own deadline.
            assertTrue(without is GenerationFailure.MuxTimedOut, "a 401 upgrade on this platform ends at the deadline, was $without")
        }
        assertReady(handshake(client, url, cookie = ContractFixtures.SESSION_COOKIE))
    }

    @Test
    fun aMuxUpgradeRefusedWith401IsReportedOrItIsNot() = test { client ->
        // The socket on its own, with a connect deadline far longer than the loop's, to see how a
        // refused upgrade ends: reported at once (JVM), or left to a timeout (Darwin).
        val closed = CompletableDeferred<Throwable?>()
        val sink = object : WsChannelSink {
            override fun onOpen() = Unit
            override fun onMessage(text: String) = Unit
            override fun onClosed(cause: Throwable?) {
                closed.complete(cause)
            }
        }
        val socket = KtorMuxSocket("${rig.sessionUrl}/api/remote.mux", client, sink, connectTimeoutMs = 8_000)
        val started = TimeSource.Monotonic.markNow()
        socket.start()
        val outcome = try {
            withTimeout(20_000) { closed.await() }
        } finally {
            socket.close()
        }
        val elapsed = started.elapsedNow().inWholeMilliseconds
        println("MUX_401: the socket ended after ${elapsed}ms with ${outcome?.let { it::class.simpleName + ": " + it.message }}")
        val status = (outcome as? RpcTransportException)?.status
        if (rig.muxUnauthorizedIsReported) {
            assertEquals(401, status, "the refusal's status should reach the sink, was $outcome")
        } else {
            assertTrue(status != 401, "this platform does not report a refused 401 upgrade, but did after ${elapsed}ms: $outcome")
        }
    }

    @Test
    fun theMuxUpgradeNeedsTheRelayTokenAndSendsIt() = test { client ->
        val url = "${rig.relayUrl}/api/remote.mux"
        val without = assertFailed(handshake(client, url))
        assertEquals(rig.muxFenceVerdict, (without as? GenerationFailure.MuxFailed)?.kind, without.toString())
        assertReady(handshake(client, url, authorization = "Bearer ${ContractFixtures.RELAY_TOKEN}"))
    }

    @Test
    fun aMuxToAClosedPortFailsAsRefused() = test { client ->
        val failure = assertFailed(handshake(client, "${rig.deadUrl}/api/remote.mux"))
        assertEquals(TransportFailure.REFUSED, (failure as? GenerationFailure.MuxFailed)?.kind, failure.toString())
    }

    @Test
    fun aStreamCarriesTextUnicodeAndAFrameBiggerThanOneMebibyte() = test { client ->
        val mux = RemoteStreamMux { sink -> KtorMuxSocket("${rig.plainUrl}/api/remote.mux", client, sink) }
        mux.start()
        try {
            mux.awaitOpen()
            val stream = mux.open("ci/stream")
            assertEquals("alpha", stream.receive()?.jsonPrimitive?.content)
            assertEquals(ContractFixtures.STREAM_UNICODE, stream.receive()?.jsonPrimitive?.content)
            val third = runCatching { stream.receive() }
            if (rig.muxAcceptsLargeMessages) {
                val large = third.getOrThrow()?.jsonPrimitive?.content
                assertEquals(ContractFixtures.STREAM_LARGE_CHARS, large?.length)
                assertTrue(large!!.all { it == 'x' })
            } else {
                // Darwin: the 3 MiB message is over the 1 MiB `NSURLSession` started with, and the
                // stream fails with the OS's own refusal ("Message too long").
                val failure = third.exceptionOrNull()
                println("MUX_LARGE: a 3 MiB message failed with ${failure?.let { it::class.simpleName + ": " + it.message }}")
                assertTrue(failure is RemoteStreamException, "expected the stream to fail on a message over 1 MiB, got $third")
            }
            stream.cancel()
        } finally {
            mux.close()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun plainGet(url: String) = PlainHttpRequest(method = "GET", url = url, connectTimeoutMs = 10_000, readTimeoutMs = 10_000)

    private fun JsonObject.text(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.header(name: String): String? = this.getValue("headers").jsonObject[name]?.jsonPrimitive?.contentOrNull

    private fun hostOf(url: String): String = url.substringAfter("://")

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private sealed interface Handshake {
        data class Ready(val home: String, val clientId: String) : Handshake
        data class Failed(val failure: GenerationFailure) : Handshake
    }

    private fun assertReady(outcome: Handshake): Handshake.Ready =
        outcome as? Handshake.Ready ?: fail("expected the handshake to reach ready, got $outcome")

    private fun assertFailed(outcome: Handshake): GenerationFailure =
        (outcome as? Handshake.Failed)?.failure ?: fail("expected the handshake to fail, got $outcome")

    /** The readiness handshake exactly as the connect flow runs it, up to the first outcome. */
    private suspend fun handshake(
        client: HttpClient,
        muxUrl: String,
        authorization: String? = null,
        cookie: String? = null,
    ): Handshake {
        val result = CompletableDeferred<Handshake>()
        val sinks = object : LoopSinks {
            override fun onEventFrame(frame: RemoteEventFrame) = Unit
            override fun onConnected(generation: HostGeneration) {
                result.complete(Handshake.Ready(generation.description.home, generation.clientId))
            }
            override fun onStateChange(state: ConnectionState) = Unit
            override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
                result.complete(Handshake.Failed(failure))
            }
        }
        val loop = ConnectionLoop(
            muxFactory = { RemoteStreamMux { sink -> KtorMuxSocket(muxUrl, client, sink, authorization, cookie) } },
            sinks = sinks,
        )
        loop.start()
        try {
            return withTimeout(20_000) { result.await() }
        } finally {
            loop.stop()
        }
    }
}

/** One rig for the whole run: starting the fixtures is the expensive part, and nothing here mutates them. */
private val sharedRig: ContractRig by lazy { runBlocking { openContractRig() } }
