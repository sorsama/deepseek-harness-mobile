package com.labteto.dshmobile.core.wire

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.io.asInputStream
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Pins what the common transport seam promises its callers, against every implementation of it.
 *
 * `RpcTransport` and `RpcTransportException` moved to common code, which meant swapping
 * `java.io.IOException` and `java.io.InputStream` for kotlinx-io's types. Both swaps are supposed to
 * be invisible to the app — this is where that is checked rather than assumed. The transport tests
 * run once per [TransportKind] (OkHttp, which Android ships, and Ktor, which iOS ships), so the
 * promise is the seam's and not one library's.
 */
@RunWith(Parameterized::class)
class TransportSeamTest(private val kind: TransportKind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<TransportKind> = TransportKind.parameters()
    }

    @Test
    fun `kotlinx-io IOException is java IOException on the JVM`() {
        // The claim the whole app's error handling leans on: `catch (e: IOException)` written
        // against java.io keeps catching the common exception type. A typealias leaves no class of
        // its own behind, so the two class objects must be the very same one.
        assertSame(java.io.IOException::class.java, kotlinx.io.IOException::class.java)
    }

    @Test
    fun `a transport exception is caught by a java IOException handler`() {
        val caught = try {
            throw RpcTransportException(502, "nothing answered behind the relay")
        } catch (e: java.io.IOException) {
            e
        }
        assertTrue(caught is RpcTransportException)
        assertEquals(502, (caught as RpcTransportException).status)
    }

    @Test
    fun `an upload streams every byte and reports progress on the 64 KiB cadence`() = runBlocking {
        val payload = ByteArray(150 * 1024 + 5) { (it % 251).toByte() }
        val received = ByteArrayOutputStream()
        withServer { server ->
            server.createContext("/api/up") { exchange ->
                received.write(exchange.requestBody.readBytes())
                val reply = """{"ok":true}""".toByteArray()
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            val progress = mutableListOf<Long>()
            val response = kind.rpc("http://127.0.0.1:${server.address.port}").upload(
                path = "/api/up",
                contentType = "application/octet-stream",
                contentLength = payload.size.toLong(),
                body = ByteArrayInputStream(payload).asSource().buffered(),
                onProgress = { progress += it },
            )

            assertEquals(200, response.status)
            assertArrayEquals(payload, received.toByteArray())
            // Non-decreasing, never past the body, and ending exactly on the total.
            assertEquals(progress.sorted(), progress)
            assertEquals(payload.size.toLong(), progress.last())
            // The old `InputStream` loop reported once per 64 KiB read; kotlinx-io reads in 8 KiB
            // segments, so the cadence has to be rebuilt rather than inherited. Every report but
            // the last advances by at least 64 KiB.
            val steps = progress.zipWithNext { a, b -> b - a }
            assertTrue("steps $steps", steps.dropLast(1).all { it >= 64 * 1024 })
            assertEquals(listOf(64L * 1024, 128L * 1024, payload.size.toLong()), progress)
        }
    }

    @Test
    fun `an empty upload reports no progress at all`() = runBlocking {
        withServer { server ->
            server.createContext("/api/up") { exchange ->
                exchange.requestBody.readBytes()
                val reply = "{}".toByteArray()
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            val progress = mutableListOf<Long>()
            kind.rpc("http://127.0.0.1:${server.address.port}").upload(
                path = "/api/up",
                contentType = "application/octet-stream",
                contentLength = 0,
                body = ByteArrayInputStream(ByteArray(0)).asSource().buffered(),
                onProgress = { progress += it },
            )
            assertEquals(emptyList<Long>(), progress)
        }
    }

    @Test
    fun `a download hands the consumer the live body and its headers`() = runBlocking {
        val payload = ByteArray(200 * 1024) { (it % 241).toByte() }
        withServer { server ->
            server.createContext("/api/session.export") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"log.zip\"")
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            val copied = ByteArrayOutputStream()
            val seen = kind.rpc("http://127.0.0.1:${server.address.port}")
                .download("/api/session.export?sessionId=s1") { contentType, disposition, body ->
                    // The app's own consumer shape: stream the source into an OutputStream.
                    body.asInputStream().copyTo(copied)
                    contentType to disposition
                }

            assertEquals("application/zip" to "attachment; filename=\"log.zip\"", seen)
            assertArrayEquals(payload, copied.toByteArray())
        }
    }

    @Test
    fun `a download can also be read whole through the source`() = runBlocking {
        withServer { server ->
            server.createContext("/api/session.export") { exchange ->
                val bytes = "PK-zip-bytes".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            val text = kind.rpc("http://127.0.0.1:${server.address.port}")
                .download("/api/session.export") { _, _, body -> body.readByteArray().decodeToString() }
            assertEquals("PK-zip-bytes", text)
        }
    }

    @Test
    fun `a refused download surfaces as a transport exception with the status`() = runBlocking {
        withServer { server ->
            server.createContext("/api/session.export") { exchange ->
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            }
            val failure = try {
                kind.rpc("http://127.0.0.1:${server.address.port}")
                    .download("/api/session.export") { _, _, _ -> error("must not be consumed") }
                null
            } catch (e: RpcTransportException) {
                e
            }
            assertEquals(401, failure?.status)
        }
    }

    private suspend fun withServer(block: suspend (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.start()
        try {
            block(server)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
