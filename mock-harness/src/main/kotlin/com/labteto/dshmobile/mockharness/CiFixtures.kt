package com.labteto.dshmobile.mockharness

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Three harnesses and a handful of test-only routes, for running the transport contract against a
 * real network stack: the iOS simulator job starts this as its own process (`mock-harness
 * --ci-fixtures`) and the Kotlin/Native tests in `:core` connect to it over `NSURLSession`, and the
 * same tests run on the JVM against the very same fixtures in-process, so one set of assertions is
 * checked on both.
 *
 * - **plain**: a harness with no authentication. The ordinary `/api` surface plus the handlers
 *   whose names start with `ci/` below.
 * - **session**: harness 0.1.2's browser-session posture; every `/api` call and the mux upgrade
 *   answer 401 without the cookie, which `GET /?token=...` hands out on a 303.
 * - **relay**: a `dsh-relay`: everything outside the `/relay` prefix answers 403 without the bearer token.
 * - **keep-alive**: a bare WebSocket server that pings every 100 ms and drops a client that does not
 *   answer, which is how a test sees that a platform answers pings. The harnesses never ping.
 * - **dropping**: completes a WebSocket handshake and drops the connection with no Close frame.
 *
 * The ports are fixed for the standalone run, because a Kotlin/Native test process has no way to be
 * told where to look except by being compiled to expect it.
 */
object CiFixtures {
    const val PLAIN_PORT: Int = 3181
    const val SESSION_PORT: Int = 3182
    const val RELAY_PORT: Int = 3183

    /** The WebSocket server that pings and drops a client that does not pong. */
    const val KEEPALIVE_PORT: Int = 3184

    /** The server that completes a WebSocket handshake and then drops the connection with no Close frame. */
    const val DROP_PORT: Int = 3185

    /**
     * A relay to pair with, over plain HTTP (`tls: off`). Its code, [RelayMode.pairingCode], works
     * every time, so a script can pair through it once per screenshot; [RATE_LIMITED_CODE] is always
     * answered 429; any other code is refused. Behind the bearer it hands out it is a harness.
     */
    const val PAIR_PORT: Int = 3186

    /**
     * The same relay over TLS with a self-signed certificate, which is the relay's default posture and
     * the only thing the pinned and trust-on-first-use code can be shown against. Its key is
     * [CiFixtureServers.pairTlsFingerprint]; the standalone run prints it. Only started on request
     * (it needs the JDK's `keytool`), so the in-process JVM runs do not depend on it.
     */
    const val PAIR_TLS_PORT: Int = 3187

    /** A port nothing listens on, for the connection-refused case. */
    const val DEAD_PORT: Int = 3189

    /** The code the pairing relays always answer with a lockout. */
    const val RATE_LIMITED_CODE: String = "42900000"

    val sessionMode: SessionMode = SessionMode()
    val relayMode: RelayMode = RelayMode()

    /** The pairing relays' behaviour: [RelayMode.pairingCode] reusable, one code that is rate limited. */
    val pairRelayMode: RelayMode = RelayMode(reusableCode = true, rateLimitedCode = RATE_LIMITED_CODE)

    /** The body of `GET /api/session.export`: 300 KiB of a pattern a truncation or a reorder would break. */
    fun exportBytes(): ByteArray = ByteArray(EXPORT_SIZE) { ((it * 31 + 7) and 0xFF).toByte() }

    /** The bytes `ci/blob` returns in a multipart answer. */
    fun blobBytes(): ByteArray = ByteArray(BLOB_SIZE) { ((it * 17 + 3) and 0xFF).toByte() }

    const val EXPORT_SIZE: Int = 300 * 1024
    const val BLOB_SIZE: Int = 40 * 1024

    /** What `ci/stream` pushes straight after it opens: a plain one, a non-ASCII one, a 3 MiB one. */
    const val STREAM_LARGE_CHARS: Int = 3 shl 20
    const val STREAM_UNICODE: String = "wörld 🐋 日本語"

    /** What `/ci/ws-close` says before it closes the socket normally, and what `/ci/ws-keepalive` says after 1.5 s. */
    const val WS_CLOSE_GREETING: String = "goodbye"
    const val WS_ALIVE: String = "alive"

    /** Start all four. Ports of 0 let the OS choose, which is what the in-process JVM run uses. */
    suspend fun start(
        plainPort: Int = 0,
        sessionPort: Int = 0,
        relayPort: Int = 0,
        keepAlivePort: Int = 0,
        dropPort: Int = 0,
        pairPort: Int = 0,
        pairTlsPort: Int = 0,
        withPairTls: Boolean = false,
    ): CiFixtureServers {
        val plain = harness(plainPort)
        val session = harness(sessionPort, session = sessionMode)
        val relay = harness(relayPort, relay = relayMode)
        val pair = harness(pairPort, relay = pairRelayMode)
        val identity = if (withPairTls) TlsIdentity.generate() else null
        val pairTls = identity?.let {
            harness(0, relay = pairRelayMode.copy(fingerprint = it.spkiPin), tls = it, tlsPort = pairTlsPort)
        }
        val keepAlive = keepAliveServer(keepAlivePort)
        val plainBound = plain.start()
        val sessionBound = session.start()
        val relayBound = relay.start()
        val pairBound = pair.start()
        pairTls?.start()
        keepAlive.start(wait = false)
        val dropping = DroppingServer(dropPort)
        return CiFixtureServers(
            plain = plain,
            session = session,
            relay = relay,
            pair = pair,
            pairTls = pairTls,
            keepAlive = keepAlive,
            dropping = dropping,
            plainPort = plainBound,
            sessionPort = sessionBound,
            relayPort = relayBound,
            pairPort = pairBound,
            pairTlsPort = pairTls?.boundTlsPort,
            pairTlsFingerprint = identity?.spkiPin,
            keepAlivePort = keepAlive.engine.resolvedConnectors().first().port,
            dropPort = dropping.port,
        )
    }

    /**
     * A WebSocket server for the one thing the harness fixtures cannot show: a host that pings. It pings
     * every 100 ms and closes a client that has not answered within 500 ms, then says [WS_ALIVE] after
     * 1.5 s, so a client that receives it answered the pings the whole time. (The real harness pings
     * every 30 s; the short period only makes the proof fast.)
     */
    private fun keepAliveServer(port: Int): KeepAliveServer =
        embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(WebSockets) {
                pingPeriodMillis = 100
                timeoutMillis = 500
            }
            routing {
                webSocket("/ci/ws-keepalive") {
                    delay(1_500)
                    send(Frame.Text(WS_ALIVE))
                    delay(3_000)
                }
            }
        }

    private fun harness(
        port: Int,
        session: SessionMode? = null,
        relay: RelayMode? = null,
        tls: TlsIdentity? = null,
        tlsPort: Int = 0,
    ): MockHarness =
        MockHarness(port = port, session = session, relay = relay, extraRoutes = { ciRoutes() }, tls = tls, tlsPort = tlsPort)
            .also(::configure)

    private fun configure(harness: MockHarness) {
        harness.sessionExportBytes = exportBytes()
        // Echoes the call's named arguments, so a body that does not survive the wire shows up.
        harness.on("ci/echo") { payload -> (payload as? JsonObject)?.get("args") ?: JsonNull }
        // A refusal that is the harness's own answer and not a carrier failure.
        harness.onFail("ci/refuse") { RpcErrorData(code = "ci/refused", message = "refused on purpose") }
        // A result carrying raw bytes, answered as harness 0.1.7 does: `multipart/form-data`.
        harness.binaryRemote("ci", "blob", emptySet()) { _ ->
            buildJsonObject {
                put("name", "blob.bin")
                put("data", JsonNull)
            } to blobBytes()
        }
        // Items sent as soon as the stream opens: text, non-ASCII text, and one past Darwin's own
        // 1 MiB default message limit.
        harness.onStream("ci/stream") { _ ->
            listOf(JsonPrimitive("alpha"), JsonPrimitive(STREAM_UNICODE), JsonPrimitive("x".repeat(STREAM_LARGE_CHARS)))
        }
    }

    /** Routes that exist only so a client can be asked what it really sent, or be handed an awkward answer. */
    private fun Route.ciRoutes() {
        // Echoes the request exactly as the server read it: method, target, every header (names
        // lower-cased) and the body, length and text. This is how a test learns what a platform's
        // HTTP stack put on the wire, `Host` and `Cookie` above all.
        route("/ci/echo-request") {
            handle {
                val bytes = call.receive<ByteArray>()
                val headers = buildJsonObject {
                    call.request.headers.forEach { name, values -> put(name.lowercase(), values.joinToString(", ")) }
                }
                val answer = buildJsonObject {
                    put("method", call.request.httpMethod.value)
                    put("target", call.request.uri)
                    put("headers", headers)
                    put("bodyLength", bytes.size)
                    put("body", bytes.decodeToString())
                }
                call.respondText(answer.toString(), ContentType.Application.Json)
            }
        }
        // Any status the client names, with a `Retry-After` on a 429.
        route("/ci/status/{code}") {
            handle {
                val code = call.parameters["code"]?.toIntOrNull() ?: 400
                if (code == 429) call.response.header("Retry-After", "7")
                call.respondText("status $code", ContentType.Text.Plain, HttpStatusCode.fromValue(code))
            }
        }
        // A redirect the client must not follow.
        route("/ci/redirect") {
            handle {
                call.response.header("Location", "/ci/echo-request")
                call.respondText("", status = HttpStatusCode.Found)
            }
        }
        // Two `Set-Cookie` headers on one response, which some stacks fold into one value.
        route("/ci/two-cookies") {
            handle {
                call.response.header("Set-Cookie", "a=1; Path=/")
                call.response.header("Set-Cookie", "b=2; Path=/")
                call.respondText("ok")
            }
        }
        // Accepts the request and never answers in time, for deadlines and cancellation.
        route("/ci/hang") {
            handle {
                delay(60_000)
                call.respondText("late")
            }
        }
        // A server that answers 200 with a web page where a harness answers a JSON envelope.
        post("/api/ci/html") {
            call.respondText("<html><body>not a harness</body></html>", ContentType.Text.Html)
        }
        // A 401 that asks for credentials, as an HTTP-authenticating server does. `NSURLSession` turns such
        // an answer into an authentication challenge for the delegate, a different path from a bare 401
        // (which is what the harness fixtures send), and the status must survive it.
        route("/ci/ws-challenge") {
            handle {
                call.response.header("WWW-Authenticate", "Basic realm=\"dsh\"")
                call.respondText("unauthorized", status = HttpStatusCode.Unauthorized)
            }
        }
        // A host that closes the mux socket politely: one message, then a Close frame with the normal
        // code. This is the case an OkHttp WebSocket without an `onClosing` handler never notices.
        webSocket("/ci/ws-close") {
            send(Frame.Text(WS_CLOSE_GREETING))
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
    }
}

internal typealias KeepAliveServer = EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

/**
 * Completes the WebSocket handshake by hand and drops the TCP connection 300 ms later with no Close
 * frame: a link that dies, as opposed to a host that ends the socket. Ktor's server cannot do this (it
 * always closes a session politely), so it is a bare socket. Any path is accepted.
 */
internal class DroppingServer(port: Int) {
    private val server = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { drop(client) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun drop(socket: Socket) {
        socket.use {
            val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            var key: String? = null
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) key = line.substringAfter(':').trim()
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray()),
            )
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n\r\n"
                        ).toByteArray(),
                )
                flush()
            }
            Thread.sleep(300)
        }
    }

    fun stop() {
        runCatching { server.close() }
    }
}

/** The running fixture servers (three harnesses, the keep-alive WebSocket server, the dropping one) and where they listen. */
class CiFixtureServers internal constructor(
    private val plain: MockHarness,
    private val session: MockHarness,
    private val relay: MockHarness,
    private val pair: MockHarness,
    private val pairTls: MockHarness?,
    private val keepAlive: KeepAliveServer,
    private val dropping: DroppingServer,
    val plainPort: Int,
    val sessionPort: Int,
    val relayPort: Int,
    val pairPort: Int,
    /** The TLS pairing relay's port, or null when it was not asked for. */
    val pairTlsPort: Int?,
    /** `base64(sha256(SubjectPublicKeyInfo))` of the TLS pairing relay's certificate, or null without one. */
    val pairTlsFingerprint: String?,
    val keepAlivePort: Int,
    val dropPort: Int,
) {
    val plainUrl: String get() = "http://127.0.0.1:$plainPort"
    val sessionUrl: String get() = "http://127.0.0.1:$sessionPort"
    val relayUrl: String get() = "http://127.0.0.1:$relayPort"
    val pairUrl: String get() = "http://127.0.0.1:$pairPort"
    val pairTlsUrl: String? get() = pairTlsPort?.let { "https://127.0.0.1:$it" }
    val keepAliveUrl: String get() = "http://127.0.0.1:$keepAlivePort"
    val dropUrl: String get() = "http://127.0.0.1:$dropPort"

    suspend fun stop() {
        plain.stop()
        session.stop()
        relay.stop()
        pair.stop()
        pairTls?.stop()
        keepAlive.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        dropping.stop()
    }
}
