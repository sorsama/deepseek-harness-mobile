package com.labteto.dshmobile.core.wire

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * What the launch-token exchange and the relay calls can rely on from a [PlainHttp], asserted once
 * against OkHttp and once against Ktor: no redirect is followed, any status is an answer, repeated
 * headers stay separate, a request that gets no answer is an [IOException] that classifies, and the
 * body is read only when asked for.
 */
@RunWith(Parameterized::class)
class PlainHttpContractTest(private val kind: TransportKind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<TransportKind> = TransportKind.parameters()
    }

    private val json = "application/json"

    private fun get(url: String, readBody: Boolean = true, readTimeoutMs: Long = 5_000) =
        PlainHttpRequest("GET", url, connectTimeoutMs = 5_000, readTimeoutMs = readTimeoutMs, readBody = readBody)

    private suspend fun failureOf(block: suspend () -> Unit): IOException {
        try {
            block()
        } catch (e: IOException) {
            return e
        }
        fail("expected the request to fail")
        throw AssertionError()
    }

    @Test
    fun `a get sends the headers it was given and none of its own content type`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, "hello", "text/plain") }.start().use { server ->
            val request = PlainHttpRequest(
                method = "GET",
                url = server.baseUrl + "/relay/health",
                headers = listOf("Accept" to json, "X-Probe" to "1"),
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
            )
            val response = kind.plain().execute(request)

            assertEquals(200, response.status)
            assertEquals("hello", response.body)
            val seen = server.seen.single()
            assertEquals("GET", seen.method)
            assertEquals("/relay/health", seen.rawPath)
            assertEquals(json, seen.header("accept"))
            assertEquals("1", seen.header("x-probe"))
            assertEquals("127.0.0.1:${server.port}", seen.header("host"))
            assertNull(seen.header("content-type"))
            assertNull(seen.header("authorization"))
            assertNull(seen.header("cookie"))
        }
    }

    @Test
    fun `a post sends its body as utf-8 under the content type it names`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, """{"ok":true}""", json) }.start().use { server ->
            val request = PlainHttpRequest(
                method = "POST",
                url = server.baseUrl + "/relay/pair",
                headers = listOf("Accept" to json),
                body = """{"code":"4821","name":"Pixel ${233.toChar()}"}""",
                bodyContentType = "application/json; charset=utf-8",
                connectTimeoutMs = 5_000,
                readTimeoutMs = 5_000,
            )
            val response = kind.plain().execute(request)

            assertEquals(200, response.status)
            val seen = server.seen.single()
            assertEquals("POST", seen.method)
            assertEquals("application/json; charset=utf-8", seen.header("content-type")?.lowercase())
            assertEquals("""{"code":"4821","name":"Pixel ${233.toChar()}"}""", String(seen.body, Charsets.UTF_8))
            // The two bytes of é, not one: UTF-8 on the wire.
            assertTrue(seen.body.any { it == 0xC3.toByte() })
            assertEquals(json, seen.header("accept"))
        }
    }

    @Test
    fun `a redirect is an answer and is not followed`() = runBlocking {
        RecordingServer().route("/") { ex, _ ->
            ex.reply(303, ByteArray(0), headers = mapOf("Location" to "./", "Set-Cookie" to "dsh-auth-test=session; Path=/; HttpOnly"))
        }.start().use { server ->
            val response = kind.plain().execute(get(server.baseUrl + "/?token=abc"))

            assertEquals(303, response.status)
            assertEquals(listOf("./"), response.headers("Location"))
            assertEquals(listOf("dsh-auth-test=session; Path=/; HttpOnly"), response.headers("set-cookie"))
            assertEquals("the redirect must not be chased", 1, server.seen.size)
            assertEquals("/", server.seen.single().rawPath)
            assertEquals("token=abc", server.seen.single().rawQuery)
        }
    }

    @Test
    fun `any status is an answer and repeated headers stay separate`() = runBlocking {
        RecordingServer().route("/") { ex, _ ->
            ex.responseHeaders.add("Set-Cookie", "a=1; Path=/")
            ex.responseHeaders.add("Set-Cookie", "dsh-auth-x=2; Path=/")
            ex.reply(429, """{"error":"slow down"}""", json, headers = mapOf("Retry-After" to "17"))
        }.start().use { server ->
            val response = kind.plain().execute(get(server.baseUrl))

            assertEquals(429, response.status)
            assertEquals("""{"error":"slow down"}""", response.body)
            assertEquals(listOf("17"), response.headers("RETRY-AFTER"))
            assertEquals(listOf("a=1; Path=/", "dsh-auth-x=2; Path=/"), response.headers("Set-Cookie"))
            assertEquals(emptyList<String>(), response.headers("X-Absent"))
        }
    }

    @Test
    fun `the body is read only when it is asked for`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> ex.reply(200, "x".repeat(50_000), "text/plain", mapOf("X-Seen" to "yes")) }.start().use { server ->
            val without = kind.plain().execute(get(server.baseUrl, readBody = false))
            assertEquals(200, without.status)
            assertEquals("", without.body)
            assertEquals(listOf("yes"), without.headers("x-seen"))

            val with = kind.plain().execute(get(server.baseUrl, readBody = true))
            assertEquals(50_000, with.body.length)
        }
    }

    @Test
    fun `a refused connection is an io error that classifies as refused`() = runBlocking {
        val closedPort = ServerSocket(0).use { it.localPort }
        val failure = failureOf { kind.plain().execute(get("http://127.0.0.1:$closedPort/")) }
        assertEquals(TransportFailure.REFUSED, TransportFailures.classify(failure))
    }

    @Test
    fun `a host that does not resolve classifies as dns`() = runBlocking {
        val failure = failureOf { kind.plain().execute(get("http://dsh-no-such-host.invalid:3080/")) }
        assertEquals(TransportFailure.DNS, TransportFailures.classify(failure))
    }

    @Test
    fun `the read deadline is the request's and classifies as a timeout`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> Thread.sleep(2_500); ex.reply(200, "late") }.start().use { server ->
            val started = System.nanoTime()
            val failure = failureOf { kind.plain().execute(get(server.baseUrl, readTimeoutMs = 400)) }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(TransportFailure.TIMEOUT, TransportFailures.classify(failure))
            assertTrue("gave up after ${elapsedMs}ms, the deadline was 400ms", elapsedMs < 2_000)
        }
    }

    @Test
    fun `a url that is not http is the callers mistake and not a transport failure`() = runBlocking {
        for (bad in listOf("ftp://host/", "not a url")) {
            try {
                kind.plain().execute(get(bad))
                fail("<$bad> must be refused")
            } catch (expected: IllegalArgumentException) {
                // refused before any request
            } catch (unexpected: IOException) {
                fail("<$bad> was reported as a transport failure: $unexpected")
            }
        }
    }

    @Test
    fun `cancelling the caller cancels the request and is not a failure`() = runBlocking {
        RecordingServer().route("/") { ex, _ -> Thread.sleep(3_000); ex.reply(200, "late") }.start().use { server ->
            val seenByCaller = AtomicReference<Throwable?>()
            val job = launch(Dispatchers.Default) {
                try {
                    kind.plain().execute(get(server.baseUrl, readTimeoutMs = 10_000))
                } catch (e: Throwable) {
                    seenByCaller.set(e)
                    throw e
                }
            }
            delay(300)
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue("cancel took ${(System.nanoTime() - started) / 1_000_000}ms", (System.nanoTime() - started) / 1_000_000 < 2_000)
            assertNotNull(seenByCaller.get())
            assertTrue(seenByCaller.get().toString(), seenByCaller.get() is CancellationException)
        }
    }
}
