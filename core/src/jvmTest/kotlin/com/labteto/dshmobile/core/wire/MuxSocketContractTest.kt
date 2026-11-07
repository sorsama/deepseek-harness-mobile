package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.mockharness.RelayMode
import com.labteto.dshmobile.mockharness.SessionMode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The `MuxSocket` contract, against a real WebSocket server, for every implementation of it.
 *
 * `MuxSocketSeamTest` drives the mux with a socket that is only a [MuxSocket]; this is the other
 * half of that promise — that the sockets which ship (`WsChannel` on OkHttp, `KtorMuxSocket` on
 * Ktor) behave as the mux assumes. The credential rules and the rejected-upgrade shape run against
 * the mock harness in its relay and browser-session postures; framing, ordering, close and
 * keep-alive run against a small purpose-built server.
 */
@RunWith(Parameterized::class)
class MuxSocketContractTest(private val kind: TransportKind) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun kinds(): List<TransportKind> = TransportKind.parameters()

        private const val RELAY_AUTH = "Bearer relay-test-token"
        private const val SESSION_COOKIE = "dsh-auth-test=session-test"
    }

    private val cleanup = CopyOnWriteArrayList<() -> Unit>()

    @After
    fun tearDown() = cleanup.reversed().forEach { runCatching(it) }

    /** Everything a socket tells its sink, in order, with latches to wait on. */
    private class Probe : WsChannelSink {
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val messages = ConcurrentLinkedQueue<String>()
        val closeCalls = AtomicInteger()
        val events = CopyOnWriteArrayList<String>()

        @Volatile
        var failure: Throwable? = null

        override fun onOpen() {
            events += "open"
            opened.countDown()
        }

        override fun onMessage(text: String) {
            events += "message"
            messages += text
        }

        override fun onClosed(cause: Throwable?) {
            events += "closed"
            failure = cause
            closeCalls.incrementAndGet()
            closed.countDown()
        }

        fun awaitMessages(count: Int, seconds: Long = 15) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (messages.size < count && System.nanoTime() < deadline) Thread.sleep(10)
        }
    }

    private fun waitFor(seconds: Long = 10, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(10)
        }
        return true
    }

    private fun harness(relay: RelayMode? = null, session: SessionMode? = null): String {
        val harness = MockHarness(port = 0, relay = relay, session = session)
        val port = runBlocking { harness.start() }
        cleanup += { runBlocking { harness.stop() } }
        return "http://127.0.0.1:$port"
    }

    private fun socket(url: String, probe: Probe, authorization: String? = null, cookie: String? = null): MuxSocket =
        kind.socket(url, probe, authorization, cookie).also { cleanup += { it.close() } }

    // ------------------------------------------------------------------ the handshake

    @Test
    fun `a mux over this socket opens a stream and hears the host's first frame`() = runBlocking {
        val base = harness(relay = RelayMode())
        val mux = RemoteStreamMux { sink -> kind.socket("$base/api/remote.mux", sink, RELAY_AUTH, null) }
        cleanup += { mux.close() }
        mux.start()
        withTimeout(15_000) { mux.awaitOpen() }

        val stream = mux.open("\$events")
        val ready = withTimeout(15_000) { stream.receive() }!!.jsonObject
        assertEquals("ready", ready["type"]!!.jsonPrimitive.content)
        stream.cancel()
        mux.close()
    }

    @Test
    fun `an upgrade without the relay credential is a 403 carrying the fence marker`() {
        val base = harness(relay = RelayMode())
        val probe = Probe()
        socket("$base/api/remote.mux", probe, authorization = null).start()

        assertTrue(probe.closed.await(15, TimeUnit.SECONDS))
        assertEquals("the socket must not report open", 1L, probe.opened.count)
        val failure = probe.failure as RpcTransportException
        assertEquals(403, failure.status)
        assertEquals(carrierMessage(403), failure.message)
        // Not "could not connect": the answer is classifiable without reading English.
        assertEquals(TransportFailure.TRUST_FENCE, TransportFailures.classify(failure))
    }

    @Test
    fun `an upgrade without a browser session is a 401 and the cookie opens it`() {
        val base = harness(session = SessionMode())
        val refused = Probe()
        socket("$base/api/remote.mux", refused).start()
        assertTrue(refused.closed.await(15, TimeUnit.SECONDS))
        val failure = refused.failure as RpcTransportException
        assertEquals(401, failure.status)
        // 401 and 403 mean different things and send the person to different fixes.
        assertEquals(TransportFailure.UNAUTHENTICATED, TransportFailures.classify(failure))

        val accepted = Probe()
        socket("$base/api/remote.mux", accepted, cookie = SESSION_COOKIE).start()
        assertTrue(accepted.opened.await(15, TimeUnit.SECONDS))
        assertNull(accepted.failure)
    }

    @Test
    fun `the credential and the host header are on the upgrade request itself`() {
        val server = WsTestServer().also { cleanup += { it.stop() } }
        val probe = Probe()
        socket(server.url("/echo"), probe, authorization = RELAY_AUTH, cookie = SESSION_COOKIE).start()
        assertTrue(probe.opened.await(15, TimeUnit.SECONDS))

        // The client hears 101 a moment before the server's handler body runs.
        assertTrue(waitFor { server.upgradeHeaders.isNotEmpty() })
        val headers = server.upgradeHeaders.single()
        assertEquals(RELAY_AUTH, headers["authorization"])
        assertEquals(SESSION_COOKIE, headers["cookie"])
        assertEquals("127.0.0.1:${server.port}", headers["host"])
    }

    @Test
    fun `a socket that cannot connect reports the failure to its sink`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val probe = Probe()
        socket("http://127.0.0.1:$closedPort/api/remote.mux", probe).start()

        assertTrue(probe.closed.await(15, TimeUnit.SECONDS))
        assertNotNull(probe.failure)
        assertEquals(1L, probe.opened.count)
        assertEquals(TransportFailure.REFUSED, TransportFailures.classify(probe.failure))
    }

    // ------------------------------------------------------------------ framing and ordering

    @Test
    fun `messages queued before the handshake finishes are sent, whole and in order`() {
        val server = WsTestServer().also { cleanup += { it.stop() } }
        val probe = Probe()
        val socket = socket(server.url("/echo"), probe)
        socket.start()
        // Sent while the upgrade is still in flight, as the mux does when it opens its streams
        // straight after `start()`: accepted now, written as soon as the socket is up.
        val sent = (0 until 200).map { """{"type":"open","streamId":"$it","endpoint":"e","payload":{"é":"$it"}}""" }
        sent.forEach { assertTrue("send #${sent.indexOf(it)} was refused", socket.send(it)) }

        probe.awaitMessages(sent.size)
        assertEquals(sent, probe.messages.toList())
        assertEquals("open", probe.events.first())
    }

    @Test
    fun `a three megabyte message arrives as one message`() {
        val server = WsTestServer().also { cleanup += { it.stop() } }
        val probe = Probe()
        socket(server.url("/big"), probe).start()

        probe.awaitMessages(1)
        assertEquals(1, probe.messages.size)
        assertEquals(3 * 1024 * 1024, probe.messages.single().length)
    }

    @Test
    fun `send is false before start and after close, and start and close are idempotent`() {
        val server = WsTestServer().also { cleanup += { it.stop() } }
        val probe = Probe()
        val socket = socket(server.url("/echo"), probe)

        assertFalse("nothing is connected yet", socket.send("x"))
        socket.start()
        socket.start()
        assertTrue(probe.opened.await(15, TimeUnit.SECONDS))
        assertTrue(waitFor { server.connections.get() >= 1 })
        Thread.sleep(200)
        assertEquals("start twice is still one connection", 1, server.connections.get())
        assertTrue(socket.send("hello"))

        socket.close()
        socket.close()
        assertFalse(socket.send("after close"))
        // A socket that was closed on purpose may say nothing or may report the cancel, but never twice.
        Thread.sleep(300)
        assertTrue(probe.closeCalls.get() <= 1)
    }

    @Test
    fun `a clean close from the host is reported as a close without a cause`() {
        // WsChannel has no `onClosing` handler, so it never answers the host's Close frame and
        // OkHttp never reaches `onClosed`: a host that closes the mux gracefully goes unnoticed
        // until the next write or read fails. That is how the Android app behaves today and is left
        // as it is (this change does not touch Android); KtorMuxSocket reports the close.
        assumeTrue("OkHttp's WsChannel does not report a host-initiated clean close", kind == TransportKind.KTOR)
        val server = WsTestServer().also { cleanup += { it.stop() } }
        val probe = Probe()
        val socket = socket(server.url("/close-clean"), probe)
        socket.start()

        assertTrue(probe.closed.await(15, TimeUnit.SECONDS))
        assertNull("a normal close is not a failure", probe.failure)
        assertEquals(1, probe.closeCalls.get())
        assertEquals(listOf("open", "closed"), probe.events.toList())
        assertFalse("the socket is gone", socket.send("late"))
    }

    @Test
    fun `a connection that drops with no close frame is a failure, not a clean close`() {
        val server = DroppingServer().also { cleanup += { it.close() } }
        val probe = Probe()
        socket("http://127.0.0.1:${server.port}/api/remote.mux", probe).start()

        assertTrue(probe.opened.await(15, TimeUnit.SECONDS))
        assertTrue(probe.closed.await(15, TimeUnit.SECONDS))
        // "The host closed the stream" and "the link died" explain the same reconnect differently.
        assertNotNull("an abrupt drop must be reported as a failure", probe.failure)
        assertEquals(1, probe.closeCalls.get())
    }

    // ------------------------------------------------------------------ keep-alive

    @Test
    fun `an idle socket answers the host's pings, so the host does not drop it`() {
        // This server pings every 100ms and closes a client that has not answered within 500ms.
        // The handler stays silent for 1.5s, then speaks; if the client did not pong, it never
        // hears that, and has been disconnected instead.
        val server = WsTestServer().also { cleanup += { it.stop() } }
        val probe = Probe()
        socket(server.url("/pinged"), probe).start()

        probe.awaitMessages(1, seconds = 10)
        assertEquals(listOf("alive"), probe.messages.toList())
        assertEquals("still connected", 1L, probe.closed.count)
        assertNull(probe.failure)
    }

    /** Completes the WebSocket handshake by hand, then drops the TCP connection with no Close frame. */
    private class DroppingServer : AutoCloseable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            Thread {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        var key: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
                        }
                        val accept = java.util.Base64.getEncoder().encodeToString(
                            java.security.MessageDigest.getInstance("SHA-1")
                                .digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
                        )
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
                            )
                            flush()
                        }
                        Thread.sleep(300)
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = runCatching { server.close() }.let { }
    }

    // ------------------------------------------------------------------ the test server

    /** A WebSocket server with one route per behaviour the contract needs. */
    private class WsTestServer {
        val upgradeHeaders = CopyOnWriteArrayList<Map<String, String>>()
        val connections = AtomicInteger()

        private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(WebSockets) {
                pingPeriodMillis = 100
                timeoutMillis = 500
            }
            routing {
                webSocket("/echo") {
                    connections.incrementAndGet()
                    upgradeHeaders += call.request.headers.entries().associate { (k, v) -> k.lowercase() to v.first() }
                    for (frame in incoming) {
                        if (frame is Frame.Text) send(Frame.Text(frame.readText()))
                    }
                }
                webSocket("/big") {
                    send(Frame.Text("x".repeat(3 * 1024 * 1024)))
                    delay(5_000)
                }
                webSocket("/close-clean") {
                    close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
                }
                webSocket("/pinged") {
                    delay(1_500)
                    send(Frame.Text("alive"))
                    delay(3_000)
                }
            }
        }.also { it.start(wait = false) }

        val port: Int = runBlocking { server.engine.resolvedConnectors().first().port }

        fun url(path: String) = "http://127.0.0.1:$port$path"

        fun stop() = server.stop(0, 0)
    }
}
