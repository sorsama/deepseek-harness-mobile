package com.labteto.dshmobile.mockharness

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
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
 *
 * The ports are fixed for the standalone run, because a Kotlin/Native test process has no way to be
 * told where to look except by being compiled to expect it.
 */
object CiFixtures {
    const val PLAIN_PORT: Int = 3181
    const val SESSION_PORT: Int = 3182
    const val RELAY_PORT: Int = 3183

    /** A port nothing listens on, for the connection-refused case. */
    const val DEAD_PORT: Int = 3189

    val sessionMode: SessionMode = SessionMode()
    val relayMode: RelayMode = RelayMode()

    /** The body of `GET /api/session.export`: 300 KiB of a pattern a truncation or a reorder would break. */
    fun exportBytes(): ByteArray = ByteArray(EXPORT_SIZE) { ((it * 31 + 7) and 0xFF).toByte() }

    /** The bytes `ci/blob` returns in a multipart answer. */
    fun blobBytes(): ByteArray = ByteArray(BLOB_SIZE) { ((it * 17 + 3) and 0xFF).toByte() }

    const val EXPORT_SIZE: Int = 300 * 1024
    const val BLOB_SIZE: Int = 40 * 1024

    /** What `ci/stream` pushes straight after it opens: a plain one, a non-ASCII one, a 3 MiB one. */
    const val STREAM_LARGE_CHARS: Int = 3 shl 20
    const val STREAM_UNICODE: String = "wörld 🐋 日本語"

    /** Start all three. Ports of 0 let the OS choose, which is what the in-process JVM run uses. */
    suspend fun start(
        plainPort: Int = 0,
        sessionPort: Int = 0,
        relayPort: Int = 0,
    ): CiFixtureServers {
        val plain = harness(plainPort)
        val session = harness(sessionPort, session = sessionMode)
        val relay = harness(relayPort, relay = relayMode)
        return CiFixtureServers(
            plain = plain,
            session = session,
            relay = relay,
            plainPort = plain.start(),
            sessionPort = session.start(),
            relayPort = relay.start(),
        )
    }

    private fun harness(port: Int, session: SessionMode? = null, relay: RelayMode? = null): MockHarness =
        MockHarness(port = port, session = session, relay = relay, extraRoutes = { ciRoutes() }).also(::configure)

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
    }
}

/** The three running fixture harnesses and where they listen. */
class CiFixtureServers internal constructor(
    private val plain: MockHarness,
    private val session: MockHarness,
    private val relay: MockHarness,
    val plainPort: Int,
    val sessionPort: Int,
    val relayPort: Int,
) {
    val plainUrl: String get() = "http://127.0.0.1:$plainPort"
    val sessionUrl: String get() = "http://127.0.0.1:$sessionPort"
    val relayUrl: String get() = "http://127.0.0.1:$relayPort"

    suspend fun stop() {
        plain.stop()
        session.stop()
        relay.stop()
    }
}
