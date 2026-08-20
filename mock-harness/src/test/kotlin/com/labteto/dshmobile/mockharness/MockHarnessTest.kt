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
    fun hostDescribeReturnsVersion() {
        val response = post("/api/host.describe", envelope("host.describe"))
        assertEquals(200, response.statusCode())
        val body = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals("server-response", body["type"]!!.jsonPrimitive.content)
        val result = body["result"]!!.jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        val value = result["value"]!!.jsonObject
        assertEquals("0.1.0-rc.8", value["version"]!!.jsonPrimitive.content)
        assertEquals("C:\\demo", value["cwd"]!!.jsonPrimitive.content)
        assertEquals(0, value["attachedSessions"]!!.jsonPrimitive.int)
        // Required from 0.1.0-rc.8, and the field the client reads to decide which
        // `commands/execute` shape this host accepts.
        assertEquals("C:\\Users\\demo", value["home"]!!.jsonPrimitive.content)
        assertTrue(value["canOpenPath"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aRemoteHeldToItsDescriptorRefusesTheWrongArgumentShape() {
        // The gateway refuses an args object that does not match its descriptor, for a missing key
        // as readily as an unexpected one. That is the whole reason `commands/execute` cannot be
        // written once for every harness release, so the mock has to say no the same way.
        harness.remote("commands", "execute", setOf("agentId", "line", "images")) {
            Json.parseToJsonElement("""{"commandId":"c1"}""")
        }

        val accepted = post(
            "/api/commands/execute",
            envelope("commands/execute", """{"args":{"agentId":"s1","line":"/compact","images":[]}}"""),
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
        assertEquals("internal", error["code"]!!.jsonPrimitive.content)
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("missing"))

        val unexpected = post(
            "/api/commands/execute",
            envelope(
                "commands/execute",
                """{"args":{"agentId":"s1","line":"/compact","images":[],"mode":"queue"}}""",
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
        // 0.1.0-rc.8 added the per-side cap and lowered the per-image byte cap to 3.5MB.
        assertEquals(2_000, limits["maxImageDimension"]!!.jsonPrimitive.int)
        assertEquals(3_670_016, limits["maxImageBytes"]!!.jsonPrimitive.int)
    }

    @Test
    fun unregisteredMethodReturnsInternalError() {
        val response = post("/api/no.such.method", envelope("no.such.method"))
        assertEquals(200, response.statusCode())
        val result = Json.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
        assertEquals(false, result["ok"]!!.jsonPrimitive.boolean)
        val error = result["error"]!!.jsonObject
        assertEquals("internal", error["code"]!!.jsonPrimitive.content)
        assertEquals("unregistered no.such.method", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun untrustedHostHeaderIsRejectedWith403() {
        val response = post("/api/host.describe", envelope("host.describe"), hostHeader = "evil.example.com")
        assertEquals(403, response.statusCode())
    }

    @Test
    fun respondEndpointAccepts() {
        val rpcId = UUID.randomUUID().toString()
        val body = """{"type":"client-response","rpcId":"$rpcId","result":{"ok":true,"value":{}}}"""
        val response = post("/api/respond", body)
        assertEquals(200, response.statusCode())
        val json = Json.parseToJsonElement(response.body()).jsonObject
        assertEquals(true, json["accepted"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun muxWebSocketReceivesSubscribedHello() {
        val received = CompletableFuture<String>()
        val listener = object : WebSocket.Listener {
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): java.util.concurrent.CompletionStage<*>? {
                received.complete(data.toString())
                webSocket.request(1)
                return null
            }
        }
        val webSocket = http.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$port/api/events.mux"), listener)
            .get(5, TimeUnit.SECONDS)
        try {
            val frame = Json.parseToJsonElement(received.get(5, TimeUnit.SECONDS)).jsonObject
            assertEquals("server-request", frame["type"]!!.jsonPrimitive.content)
            assertTrue(frame["rpcId"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals("session/subscribed", frame["method"]!!.jsonPrimitive.content)
            val payload = frame["payload"]!!.jsonObject
            assertEquals("demo", payload["sessionId"]!!.jsonPrimitive.content)
            assertEquals(-1, payload["lastSeq"]!!.jsonPrimitive.int)
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
