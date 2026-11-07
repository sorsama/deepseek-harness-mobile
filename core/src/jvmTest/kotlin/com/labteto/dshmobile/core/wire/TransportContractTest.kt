package com.labteto.dshmobile.core.wire

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * What the harness and `dsh-relay` can observe of a carrier, and what a caller can observe of its
 * failures — asserted once, against every [TransportKind].
 *
 * Each case here is a behaviour of `OkHttpRpcTransport` that `KtorRpcTransport` had to reproduce:
 * the headers on the wire, the status-to-exception table, multipart answers, deadlines, the prefix
 * a reverse proxy mounts the harness under, and the streaming guarantees of download and upload. A
 * case that holds for only one of them is not here; it is a listed gap in `docs/IOS.md`.
 */
@RunWith(Parameterized::class)
class TransportContractTest(private val kind: TransportKind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<TransportKind> = TransportKind.parameters()
    }

    private val json = "application/json"

    // ------------------------------------------------------------------ what goes on the wire

    @Test
    fun `a unary post spells the headers the fence and the relay read`() = runBlocking {
        RecordingServer().route("/api/session.list") { ex, _ -> ex.reply(200, """{"ok":true}""", json) }.start().use { server ->
            val response = kind.rpc(server.baseUrl, authorization = "Bearer relay-token", cookie = "dsh-auth=abc")
                .post("/api/session.list", """{"hello":"wörld"}""")

            assertEquals(200, response.status)
            assertEquals("""{"ok":true}""", response.body)
            assertEquals(json, response.contentType)
            assertNull(response.multipart)
            val seen = server.seen.single()
            assertEquals("POST", seen.method)
            assertEquals("/api/session.list", seen.rawPath)
            assertEquals("127.0.0.1:${server.port}", seen.header("Host"))
            assertEquals("application/json; charset=utf-8", seen.header("Content-Type")!!.lowercase())
            assertEquals("Bearer relay-token", seen.header("Authorization"))
            assertEquals("dsh-auth=abc", seen.header("Cookie"))
            // The body went out as UTF-8 and the length matches it.
            assertEquals("""{"hello":"wörld"}""", seen.body.decodeToString())
            assertEquals(seen.body.size.toString(), seen.header("Content-Length"))
        }
    }

    @Test
    fun `no credential means no credential header at all`() = runBlocking {
        RecordingServer().route("/api/") { ex, _ -> ex.reply(200, "{}", json) }.start().use { server ->
            kind.rpc(server.baseUrl).post("/api/host.describe", "{}")
            val seen = server.seen.single()
            assertNull(seen.header("Authorization"))
            assertNull(seen.header("Cookie"))
        }
    }

    @Test
    fun `routes resolve under the reverse proxy prefix and queries are not re-encoded`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, "ok", "application/zip") }.start().use { server ->
            val transport = kind.rpc("${server.baseUrl}/team%20one/dsh/")
            transport.post("/api/session.list", "{}")
            transport.download("/api/session.export?sessionId=a%20b%2Bc&format=zip") { _, _, body -> body.readByteArray() }

            assertEquals(listOf("/team%20one/dsh/api/session.list", "/team%20one/dsh/api/session.export"), server.seen.map { it.rawPath })
            assertEquals("sessionId=a%20b%2Bc&format=zip", server.seen[1].rawQuery)
            // `Host` names the proxy's own authority, not the prefix.
            assertEquals("127.0.0.1:${server.port}", server.seen[0].header("Host"))
        }
    }

    @Test
    fun `a route outside the harness root is an argument error not a transport failure`() = runBlocking {
        RecordingServer().start().use { server ->
            val transport = kind.rpc("${server.baseUrl}/dsh")
            for (route in listOf("/../x", "//evil.example/x", "api/x")) {
                try {
                    transport.post(route, "{}")
                    fail("$route must be refused")
                } catch (expected: IllegalArgumentException) {
                    // DshApiClient reports this as "not a harness", never as a network failure.
                }
            }
            assertTrue("nothing may have been sent", server.seen.isEmpty())
        }
    }

    // ------------------------------------------------------------------ answers

    @Test
    fun `a multipart answer comes back as raw bytes with its content type`() = runBlocking {
        val bytes = "--b\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n{}\r\n--b--\r\n".toByteArray()
        RecordingServer().route("/api/") { ex, _ -> ex.reply(200, bytes, "multipart/form-data; boundary=b") }.start().use { server ->
            val response = kind.rpc(server.baseUrl).post("/api/workspaceFiles/readBytes", "{}")
            assertEquals("", response.body)
            assertEquals("multipart/form-data; boundary=b", response.contentType)
            assertArrayEquals(bytes, response.multipart)
        }
    }

    @Test
    fun `an error status is a transport exception even when the body is multipart`() = runBlocking {
        RecordingServer().route("/api/") { ex, _ -> ex.reply(500, "x", "multipart/form-data; boundary=b") }.start().use { server ->
            val failure = failureOf { kind.rpc(server.baseUrl).post("/api/x", "{}") }
            assertEquals(500, failure.status)
            assertEquals("carrier returned HTTP 500", failure.message)
        }
    }

    @Test
    fun `every non-2xx status maps to the same exception on every carrier`() = runBlocking {
        data class Case(val status: Int, val body: String, val headers: Map<String, String>, val message: String, val retryAfter: Long?)
        val fence = "harness trust fence rejected the request (HTTP 403)"
        val cases = listOf(
            Case(401, "unauthorized", emptyMap(), "harness has no browser session for this client (HTTP 401)", null),
            Case(403, "", emptyMap(), fence, null),
            Case(403, "Forbidden", emptyMap(), fence, null),
            Case(403, """{"ok":false,"error":"unparsable-host"}""", emptyMap(), "request refused before the harness: unparsable-host (HTTP 403)", null),
            Case(403, "{not json", emptyMap(), fence, null),
            Case(404, "", emptyMap(), "carrier returned HTTP 404", null),
            Case(413, "", emptyMap(), "the harness refused the request as too large (HTTP 413)", null),
            Case(429, "", mapOf("Retry-After" to "30"), "rate limited before the harness (HTTP 429)", 30),
            Case(429, "", emptyMap(), "rate limited before the harness (HTTP 429)", null),
            Case(429, "", mapOf("Retry-After" to "soon"), "rate limited before the harness (HTTP 429)", null),
            Case(429, "", mapOf("Retry-After" to "0"), "rate limited before the harness (HTTP 429)", 1),
            Case(502, "", emptyMap(), "nothing answered behind the relay (HTTP 502)", null),
            Case(500, """{"error":"boom"}""", emptyMap(), "carrier returned HTTP 500", null),
            Case(503, "", emptyMap(), "carrier returned HTTP 503", null),
        )
        for (case in cases) {
            RecordingServer().route("/api/") { ex, _ -> ex.reply(case.status, case.body, "application/json", case.headers) }.start().use { server ->
                val failure = failureOf { kind.rpc(server.baseUrl).post("/api/session.list", "{}") }
                val row = "HTTP ${case.status} ${case.body} ${case.headers}"
                assertEquals(row, case.status, failure.status)
                assertEquals(row, case.message, failure.message)
                assertEquals(row, case.retryAfter, failure.retryAfterSeconds)
            }
        }
    }

    @Test
    fun `a status that no longer reaches the caller as a classified failure still classifies`() = runBlocking {
        for ((status, expected) in listOf(
            401 to TransportFailure.UNAUTHENTICATED,
            403 to TransportFailure.TRUST_FENCE,
            404 to TransportFailure.NOT_FOUND,
            413 to TransportFailure.TOO_LARGE,
            429 to TransportFailure.RATE_LIMITED,
            502 to TransportFailure.UPSTREAM_DOWN,
            500 to TransportFailure.NOT_A_HARNESS,
        )) {
            RecordingServer().route("/api/") { ex, _ -> ex.reply(status) }.start().use { server ->
                val failure = failureOf { kind.rpc(server.baseUrl).post("/api/x", "{}") }
                assertEquals("HTTP $status", expected, TransportFailures.classify(failure))
            }
        }
    }

    // ------------------------------------------------------------------ network failures

    @Test
    fun `a refused connection is status zero and classifies as refused`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val failure = failureOf { kind.rpc("http://127.0.0.1:$closedPort").post("/api/x", "{}") }
        assertEquals(0, failure.status)
        assertTrue(failure.message!!, failure.message!!.startsWith("transport failure: "))
        assertNotNull(failure.cause)
        assertEquals(TransportFailure.REFUSED, TransportFailures.classify(failure))
    }

    @Test
    fun `a host that does not resolve classifies as dns`() = runBlocking {
        val failure = failureOf { kind.rpc("http://dsh-no-such-host.invalid:3080").post("/api/x", "{}") }
        assertEquals(0, failure.status)
        assertEquals(TransportFailure.DNS, TransportFailures.classify(failure))
    }

    @Test
    fun `the read deadline is the constructor parameter and classifies as a timeout`() = runBlocking {
        RecordingServer().route("/api/") { ex, _ -> Thread.sleep(2_500); ex.reply(200, "{}", json) }.start().use { server ->
            val started = System.nanoTime()
            val failure = failureOf { kind.rpc(server.baseUrl, connectTimeoutMs = 5_000, readTimeoutMs = 400).post("/api/x", "{}") }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(0, failure.status)
            assertEquals(TransportFailure.TIMEOUT, TransportFailures.classify(failure))
            assertTrue("gave up after ${elapsedMs}ms, the deadline was 400ms", elapsedMs < 2_000)
        }
    }

    @Test
    fun `package operations are exempt from the read deadline`() = runBlocking {
        RecordingServer().route("/api/") { ex, _ -> Thread.sleep(700); ex.reply(200, "{}", json) }.start().use { server ->
            val transport = kind.rpc(server.baseUrl, readTimeoutMs = 200)
            assertEquals(200, transport.post("/api/pluginManager/waitForInstall", "{}").status)
            assertEquals(200, transport.post("/api/pluginManager/installBundle", "{}").status)
            // …and every other path is not.
            assertEquals(TransportFailure.TIMEOUT, TransportFailures.classify(failureOf { transport.post("/api/session.list", "{}") }))
        }
    }

    @Test
    fun `cancelling the caller cancels the call and is not a transport failure`() = runBlocking {
        RecordingServer().route("/api/") { ex, _ -> Thread.sleep(3_000); ex.reply(200, "{}", json) }.start().use { server ->
            val seenByCaller = AtomicReference<Throwable?>()
            val job = launch(Dispatchers.Default) {
                try {
                    kind.rpc(server.baseUrl).post("/api/x", "{}")
                } catch (e: Throwable) {
                    seenByCaller.set(e)
                    throw e
                }
            }
            while (server.seen.isEmpty()) delay(20)
            job.cancelAndJoin()
            assertTrue("was ${seenByCaller.get()}", seenByCaller.get() is CancellationException)
        }
    }

    // ------------------------------------------------------------------ download

    @Test
    fun `a download source is live and the connection is released when the consumer returns`() = runBlocking {
        RecordingServer().route("/api/session.export") { ex, _ ->
            ex.responseHeaders.add("Content-Type", "application/zip")
            ex.sendResponseHeaders(200, 0) // chunked: the length is not known up front
            ex.responseBody.use { out ->
                repeat(10) {
                    out.write(ByteArray(1024) { 7 })
                    out.flush()
                    Thread.sleep(150)
                }
            }
        }.start().use { server ->
            val started = System.nanoTime()
            var retained: kotlinx.io.Source? = null
            val firstBytes = kind.rpc(server.baseUrl).download("/api/session.export") { _, _, body ->
                retained = body
                body.readByteArray(100)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(100, firstBytes.size)
            // The server needs ~1.5s to finish. Returning in well under that means the consumer was
            // handed bytes as they arrived, rather than a copy made after the whole body landed.
            assertTrue("took ${elapsedMs}ms", elapsedMs < 1_200)
            // …and the source must not outlive the call.
            // (Drained to the end: the part already pulled into the source's own buffer may still be
            // read, but the connection behind it is gone, so the rest cannot arrive.)
            try {
                retained!!.readByteArray()
                fail("a retained source must fail once the call has returned")
            } catch (expected: Exception) {
                // closed
            }
        }
    }

    @Test
    fun `what the consumer throws comes out as it went in`() = runBlocking {
        RecordingServer().route("/api/session.export") { ex, _ -> ex.reply(200, "zip", "application/zip") }.start().use { server ->
            val boom = IllegalStateException("disk full")
            try {
                kind.rpc(server.baseUrl).download("/api/session.export") { _, _, _ -> throw boom }
                fail("the consumer's failure must propagate")
            } catch (e: IllegalStateException) {
                // Not wrapped as a transport failure. (Compared by type and message, because
                // coroutine stack-trace recovery re-creates an exception that crosses a dispatcher
                // switch, for OkHttp as much as for Ktor; the original rides along as the cause.)
                assertEquals("disk full", e.message)
                assertTrue(e === boom || e.cause === boom)
            }
        }
    }

    @Test
    fun `a body cut short mid-download fails the read with an IOException`() = runBlocking {
        RecordingServer().route("/api/session.export") { ex, _ ->
            ex.sendResponseHeaders(200, 100_000)
            ex.responseBody.write(ByteArray(1024))
            ex.responseBody.flush()
            Thread.sleep(100)
            // Close without writing the other 99,000 bytes the headers promised.
            ex.close()
        }.start().use { server ->
            try {
                kind.rpc(server.baseUrl).download("/api/session.export") { _, _, body -> body.readByteArray() }
                fail("a truncated body must not read as a complete one")
            } catch (expected: java.io.IOException) {
                assertTrue("an incomplete body is not a carrier status", expected !is RpcTransportException)
            }
        }
    }

    @Test
    fun `a download sends the same credentials as a post`() = runBlocking {
        RecordingServer().route("/api/session.export") { ex, _ -> ex.reply(200, "zip") }.start().use { server ->
            kind.rpc(server.baseUrl, authorization = "Bearer t", cookie = "c=1").download("/api/session.export") { _, _, body -> body.readByteArray() }
            val seen = server.seen.single()
            assertEquals("GET", seen.method)
            assertEquals("Bearer t", seen.header("Authorization"))
            assertEquals("c=1", seen.header("Cookie"))
            assertEquals("127.0.0.1:${server.port}", seen.header("Host"))
        }
    }

    // ------------------------------------------------------------------ upload

    @Test
    fun `an upload declares its type and length and sends the credentials`() = runBlocking {
        val payload = ByteArray(10_000) { (it % 200).toByte() }
        RecordingServer().route("/api/session/uploadFileBinary") { ex, _ -> ex.reply(200, """{"ok":true}""", json) }.start().use { server ->
            val response = kind.rpc(server.baseUrl, authorization = "Bearer t", cookie = "c=1").upload(
                path = "/api/session/uploadFileBinary",
                contentType = "image/png",
                contentLength = payload.size.toLong(),
                body = payload.inputStream().asSource().buffered(),
                onProgress = null,
            )
            assertEquals(200, response.status)
            assertEquals("""{"ok":true}""", response.body)
            val seen = server.seen.single()
            assertEquals("POST", seen.method)
            assertEquals("image/png", seen.header("Content-Type"))
            assertEquals(payload.size.toString(), seen.header("Content-Length"))
            assertEquals("Bearer t", seen.header("Authorization"))
            assertEquals("c=1", seen.header("Cookie"))
            assertEquals("127.0.0.1:${server.port}", seen.header("Host"))
            assertArrayEquals(payload, seen.body)
        }
    }

    @Test
    fun `a refused upload carries the status and not the body's reason`() = runBlocking {
        RecordingServer().route("/api/") { ex, _ -> ex.reply(403, """{"error":"unparsable-host"}""", json) }.start().use { server ->
            val failure = failureOf {
                kind.rpc(server.baseUrl).upload("/api/session/uploadFileBinary", "image/png", 3, "abc".byteInputSource(), null)
            }
            // Parity with OkHttp, whose upload (like its download) words a refusal from the status alone.
            assertEquals(403, failure.status)
            assertEquals("harness trust fence rejected the request (HTTP 403)", failure.message)
        }
    }

    // ------------------------------------------------------------------ Ktor-only behaviour

    @Test
    fun `a redirected api call is not followed by the ktor carrier`() = runBlocking {
        assumeTrue("OkHttp's default client follows redirects; the Ktor carrier refuses them on purpose", kind == TransportKind.KTOR)
        RecordingServer().route("/api/moved") { ex, _ ->
            ex.reply(302, headers = mapOf("Location" to "http://127.0.0.1:1/elsewhere"))
        }.start().use { server ->
            val failure = failureOf { kind.rpc(server.baseUrl, cookie = "c=1").post("/api/moved", "{}") }
            assertEquals(302, failure.status)
            assertEquals("carrier returned HTTP 302", failure.message)
        }
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun failureOf(block: suspend () -> Any?): RpcTransportException = try {
        block()
        fail("expected an RpcTransportException")
        throw AssertionError()
    } catch (e: RpcTransportException) {
        e
    }

    private fun String.byteInputSource() = toByteArray().inputStream().asSource().buffered()
}
