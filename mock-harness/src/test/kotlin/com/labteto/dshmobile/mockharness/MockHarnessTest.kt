package com.labteto.dshmobile.mockharness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MockHarnessTest {

    companion object {
        private lateinit var harness: MockHarness
        private var port: Int = -1
        private lateinit var http: HttpClient

        @JvmStatic
        @BeforeClass
        fun setUp() {
            // java.net.http forbids setting the Host header by default; lift that
            // restriction so the trust-fence test can send a mismatched Host.
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host")
            http = HttpClient.newHttpClient()
            runBlocking {
                harness = MockHarness(port = 0)
                port = harness.start()
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::harness.isInitialized) {
                runBlocking { harness.stop() }
            }
        }
    }

    @Test
    fun theProbeCallAnswers() {
        // `host.describe` is gone. What a reachability probe calls now is an argument-free
        // Session Controller method, and what it proves is only that the endpoint speaks the
        // protocol — the host facts come from the ready frame instead.
        val response = post(
            "/api/session/canOpenWorkspacePath",
            envelope("session/canOpenWorkspacePath"),
        )
        assertEquals(200, response.statusCode())
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals("server-response", body["type"]!!.jsonPrimitive.content)
        val result = body["result"]!!.jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        assertTrue(result["value"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aRemoteHeldToItsDescriptorRefusesTheWrongArgumentShape() {
        // The gateway refuses an args object that does not match its descriptor, for a missing key
        // as readily as an unexpected one. That is the whole reason `commands/execute` cannot be
        // written once for every harness release, so the mock has to say no the same way.
        harness.remote("commands", "execute", setOf("agentId", "line", "submittedAttachments")) {
            Json.parseToJsonElement("""{"commandId":"c1"}""")
        }

        val accepted = post(
            "/api/commands/execute",
            envelope("commands/execute", """{"args":{"agentId":"s1","line":"/compact","submittedAttachments":[]}}"""),
        )
        val acceptedResult = Json.parseToJsonElement(accepted.body()).jsonObject["result"]!!.jsonObject
        assertTrue(acceptedResult["ok"]!!.jsonPrimitive.boolean)

        val refused = post(
            "/api/commands/execute",
            envelope("commands/execute", """{"args":{"agentId":"s1","line":"/compact"}}"""),
        )
        val refusedResult = Json.parseToJsonElement(refused.body()).jsonObject["result"]!!.jsonObject
        assertFalse(refusedResult["ok"]!!.jsonPrimitive.boolean)
        val error = refusedResult["error"]!!.jsonObject
        assertEquals("gateway/arguments-invalid", error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("missing"))

        val unexpected = post(
            "/api/commands/execute",
            envelope(
                "commands/execute",
                """{"args":{"agentId":"s1","line":"/compact","submittedAttachments":[],"mode":"queue"}}""",
            ),
        )
        val unexpectedResult = Json.parseToJsonElement(unexpected.body()).jsonObject["result"]!!.jsonObject
        assertFalse(unexpectedResult["ok"]!!.jsonPrimitive.boolean)
        assertTrue(
            unexpectedResult["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                .contains("unexpected"),
        )
    }

    @Test
    fun theImageLimitsProjectionCarriesThePerSideBound() {
        val limits = harness.imageLimitsValue()
        // 0.1.1-rc.2 raised both bounds once the host began normalizing stored images.
        assertEquals(8_192, limits["maxImageDimension"]!!.jsonPrimitive.int)
        assertEquals(20_971_520, limits["maxImageBytes"]!!.jsonPrimitive.int)
    }

    @Test
    fun unregisteredMethodReturnsInternalError() {
        val response = post("/api/no.such.method", envelope("no.such.method"))
        assertEquals(200, response.statusCode())
        val result = Json.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
        assertEquals(false, result["ok"]!!.jsonPrimitive.boolean)
        val error = result["error"]!!.jsonObject
        assertEquals("gateway/internal", error["code"]!!.jsonPrimitive.content)
        assertEquals("unregistered no.such.method", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun theBinaryUploadRouteStagesBytesAndAnswersABareResult() {
        // Harness 0.1.3's raw-byte route: octets in, a `{ok, value}` result out with no envelope
        // around it, and a receipt the session can cite. Carrier misuse is a plain status.
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/session/uploadFileBinary?sessionId=s1&name=notes.txt"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray("hello".toByteArray()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        val value = body["value"]!!.jsonObject
        assertTrue(value["receiptId"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("notes.txt", value["file"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(5, value["file"]!!.jsonObject["bytes"]!!.jsonPrimitive.int)
        val staged = harness.fileUploads.last()
        assertEquals("s1", staged.sessionId)
        assertEquals("hello", staged.bytes.decodeToString())

        val wrongType = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/session/uploadFileBinary?sessionId=s1"))
            .header("Content-Type", "text/plain")
            .POST(HttpRequest.BodyPublishers.ofString("hello"))
            .build()
        assertEquals(415, http.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode())

        val noSession = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/session/uploadFileBinary"))
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray("x".toByteArray()))
            .build()
        assertEquals(400, http.send(noSession, HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    @Test
    fun theEncodedUploadRemoteIsAgentScoped() {
        val response = post(
            "/api/fileUploads/upload",
            envelope("fileUploads/upload", """{"args":{"agentId":"s1","request":{"data":"aGVsbG8=","name":"a.bin"}}}"""),
        )
        val result = Json.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        assertEquals("a.bin", result["value"]!!.jsonObject["file"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        // The request object is decoded against a strict codec: no `data`, no upload.
        val refused = post(
            "/api/fileUploads/upload",
            envelope("fileUploads/upload", """{"args":{"agentId":"s1","request":{"name":"a.bin"}}}"""),
        )
        val refusedResult = Json.parseToJsonElement(refused.body()).jsonObject["result"]!!.jsonObject
        assertFalse(refusedResult["ok"]!!.jsonPrimitive.boolean)
        assertEquals("gateway/input-invalid", refusedResult["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun untrustedHostHeaderIsRejectedWith403() {
        val response = post("/api/host.describe", envelope("host.describe"), hostHeader = "evil.example.com")
        assertEquals(403, response.statusCode())
    }

    @Test
    fun eventResultAcceptsAnAnswerForThisGeneration() {
        val rpcId = UUID.randomUUID().toString()
        val body = """{"type":"client-request","rpcId":"$rpcId","method":"${'$'}events/result",""" +
            """"payload":{"clientId":"${harness.clientId}","eventId":"evt-1",""" +
            """"outcome":{"kind":"result","value":{}}}}"""
        val response = post("/api/${'$'}events/result", body)
        assertEquals(200, response.statusCode())
        val json = Json.parseToJsonElement(response.body()).jsonObject
        assertTrue(json["result"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun eventResultRefusesAnAnswerFromARetiredGeneration() {
        // The clientId binding is the whole point of the ready frame: an answer minted against a
        // connection that has since been replaced must not resolve a request the host has
        // already replayed to the new one.
        val rpcId = UUID.randomUUID().toString()
        val body = """{"type":"client-request","rpcId":"$rpcId","method":"${'$'}events/result",""" +
            """"payload":{"clientId":"stale","eventId":"evt-1",""" +
            """"outcome":{"kind":"result","value":{}}}}"""
        val response = post("/api/${'$'}events/result", body)
        assertEquals(200, response.statusCode())
        val result = Json.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
        assertEquals(false, result["ok"]!!.jsonPrimitive.boolean)
        assertEquals("stale-generation", result["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun openingTheEventsStreamAnswersWithReady() {
        // The mux is bidirectional: nothing arrives until the client opens a stream by name.
        // A mock that pushed on connect would let a client pass without ever sending an `open`.
        val received = CompletableFuture<String>()
        val listener = object : WebSocket.Listener {
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
                received.complete(data.toString())
                webSocket.request(1)
                return null
            }
        }
        val webSocket = http.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$port/api/remote.mux"), listener)
            .get(5, TimeUnit.SECONDS)
        try {
            webSocket.sendText(
                """{"type":"open","streamId":"1","endpoint":"${'$'}events","payload":{"args":{}}}""",
                true,
            ).get(5, TimeUnit.SECONDS)
            val frame = Json.parseToJsonElement(received.get(5, TimeUnit.SECONDS)).jsonObject
            assertEquals("item", frame["type"]!!.jsonPrimitive.content)
            assertEquals("1", frame["streamId"]!!.jsonPrimitive.content)
            val value = frame["value"]!!.jsonObject
            assertEquals("ready", value["type"]!!.jsonPrimitive.content)
            assertEquals(harness.clientId, value["clientId"]!!.jsonPrimitive.content)
            assertEquals("C:\\Users\\demo", value["host"]!!.jsonObject["home"]!!.jsonPrimitive.content)
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
        }
    }

    @Test
    fun typertRemotePathIsRoutedUnderItsComposedMethodName() {
        // The Remote gateway lives on a second path segment but shares the ordinary envelope, so a
        // client that posts a bare `{"args": …}` body — as this app's helper once did — never
        // reaches a handler at all.
        harness.on("commands/list") { Json.parseToJsonElement("""[{"name":"plan"}]""") }
        val body = """{"type":"client-request","rpcId":"${UUID.randomUUID()}",""" +
            """"method":"commands/list","payload":{"args":{"agentId":"demo"}}}"""
        val response = post("/api/commands/list", body)

        assertEquals(200, response.statusCode())
        val result = Json.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun sessionExportStreamsAnAttachment() {
        harness.sessionExportBytes = "PKzip".toByteArray()
        val request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:$port/api/session.export?sessionId=demo"),
        ).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertEquals("PKzip", response.body())
        assertTrue(
            response.headers().firstValue("content-disposition").orElse("")
                .contains("dsh-session-demo.zip"),
        )
    }

    @Test
    fun sessionExportWithoutASessionIdIsABadRequest() {
        val request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:$port/api/session.export"),
        ).GET().build()
        assertEquals(400, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    private fun post(path: String, body: String, hostHeader: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
        if (hostHeader != null) {
            builder.header("Host", hostHeader)
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun envelope(method: String, payload: String = "{}"): String =
        """{"type":"client-request","rpcId":"${UUID.randomUUID()}","method":"$method","payload":$payload}"""
}
