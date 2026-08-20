package com.labteto.dshmobile.mockharness

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.host
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A scriptable stand-in for the DeepSeek Harness HTTP/WebSocket protocol.
 *
 * The harness exposes a JSON-RPC-flavored protocol on plain HTTP:
 *  - unary calls: `POST /api/<method>` with a `client-request` envelope, answered with a
 *    `server-response` envelope carrying `{"ok": true, "value": ...}` or
 *    `{"ok": false, "error": {"code", "message", "details"}}`;
 *  - answers: `POST /api/respond` with a `client-response` envelope. An answer to a question
 *    this mock pushed is judged by [judgeQuestionResponse], the host's own acceptance law;
 *    anything else is acknowledged;
 *  - downlink-only WebSockets `/api/events.mux` and `/api/events.host`, where the server
 *    pushes `server-request` frames and the client must not send.
 *
 * A trust fence rejects every POST whose Host header is neither loopback nor listed in
 * [trustedHosts] with HTTP 403, replicated before any dispatch.
 */
class MockHarness(
    private val trustedHosts: List<String> = emptyList(),
    private val port: Int = 0,
) {
    private val okHandlers = ConcurrentHashMap<String, (JsonElement) -> JsonElement>()
    private val failHandlers = ConcurrentHashMap<String, (JsonElement) -> RpcErrorData>()
    private val asyncHandlers = ConcurrentHashMap<String, suspend (JsonElement) -> JsonElement>()
    private val muxSockets = ConcurrentHashMap.newKeySet<WebSocketSession>()
    private val pendingQuestions = ConcurrentHashMap<String, PendingQuestion>()
    private val hostSockets = ConcurrentHashMap.newKeySet<WebSocketSession>()

    @Volatile
    private var describeTransform: ((JsonObject) -> JsonObject)? = null

    /** Body served by `GET /api/session.export`; tests set this to assert on the streamed bytes. */
    @Volatile
    var sessionExportBytes: ByteArray = ByteArray(0)

    private val normalizedTrustedHosts: Set<String> =
        trustedHosts.mapTo(mutableSetOf()) { normalizeHost(it) }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    init {
        on("host.describe") { describeValue() }
        on("pluginInventory/list") { pluginInventoryValue() }
    }

    /**
     * Starts the server on [port] (0 lets the OS assign one) and returns the bound port.
     */
    suspend fun start(): Int {
        val newServer = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                get("/api/session.export") {
                    call.handleSessionExport()
                }
                post("/api/{method}") {
                    call.handleApi()
                }
                // The typert Remote gateway lives on a second path segment
                // (`/api/commands/execute`) but shares the ordinary envelope, so it maps onto the
                // same handler under the composed method name.
                post("/api/{namespace}/{method}") {
                    val namespace = call.parameters["namespace"].orEmpty()
                    val method = call.parameters["method"].orEmpty()
                    call.handleApi("$namespace/$method")
                }
                webSocket("/api/events.mux") {
                    handleMuxSocket()
                }
                webSocket("/api/events.host") {
                    handleHostSocket()
                }
            }
        }
        newServer.start(wait = false)
        server = newServer
        return newServer.engine.resolvedConnectors().first().port
    }

    /**
     * Stops the server. Safe to call even if [start] was never called.
     */
    suspend fun stop() {
        val current = server ?: return
        server = null
        withContext(Dispatchers.IO) {
            current.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    /**
     * Registers a synchronous handler for [method]; the handler maps the request payload
     * to the `ok` value of the response. Replaces any previous handler for the method.
     */
    fun on(method: String, handler: (JsonElement) -> JsonElement) {
        okHandlers[method] = handler
        failHandlers.remove(method)
        asyncHandlers.remove(method)
    }

    /**
     * Registers a failing handler for [method]; the handler maps the request payload to the
     * `ok: false` error of the response. Replaces any previous handler for the method.
     */
    fun onFail(method: String, handler: (JsonElement) -> RpcErrorData) {
        failHandlers[method] = handler
        okHandlers.remove(method)
        asyncHandlers.remove(method)
    }

    /**
     * Registers a suspend handler for [method] (e.g. one that awaits an asynchronous answer).
     * Replaces any previous handler for the method.
     */
    fun sessionHistory(method: String, handler: suspend (JsonElement) -> JsonElement) {
        asyncHandlers[method] = handler
        okHandlers.remove(method)
        failHandlers.remove(method)
    }

    /**
     * Overrides the built-in `host.describe` handler: [transform] receives the default
     * describe value and returns the value that will be served.
     *
     * Use it to serve a pre-0.1.0-rc.8 host by dropping the key that release added:
     * `describe { JsonObject(it - "home") }`.
     */
    fun describe(transform: (JsonObject) -> JsonObject) {
        describeTransform = transform
    }

    /**
     * Registers a typert remote under `namespace/method`, held to the gateway's own argument rule.
     *
     * The real gateway matches an args object against the remote's declared parameters and refuses
     * it for a missing key as readily as for an unexpected one — which is why a client cannot write
     * one `commands/execute` payload for every harness release. A mock that accepted whatever
     * arrived would be exactly blind to the mistake worth catching, so this one answers the
     * gateway's own error instead.
     *
     * @param expectedArgs the descriptor's complete argument key set.
     * @param handler maps the validated args object to the remote's return value.
     */
    fun remote(
        namespace: String,
        method: String,
        expectedArgs: Set<String>,
        handler: (JsonObject) -> JsonElement,
    ) {
        val endpoint = "$namespace/$method"
        okHandlers.remove(endpoint)
        asyncHandlers.remove(endpoint)
        failHandlers.remove(endpoint)
        okHandlers[endpoint] = { payload ->
            val args = (payload as? JsonObject)?.get("args") as? JsonObject
                ?: throw IllegalArgumentException("args must be a plain object")
            val missing = expectedArgs.filterNot { it in args.keys }
            val unexpected = args.keys.filterNot { it in expectedArgs }
            if (missing.isEmpty() && unexpected.isEmpty()) {
                handler(args)
            } else {
                throw ArgumentsInvalid(argumentsInvalidMessage(missing, unexpected))
            }
        }
    }

    /** The gateway's `arguments-invalid` refusal, thrown out of a [remote] handler. */
    class ArgumentsInvalid(override val message: String) : RuntimeException(message)

    private fun argumentsInvalidMessage(missing: List<String>, unexpected: List<String>): String {
        val clauses = buildList {
            if (missing.isNotEmpty()) add("missing " + missing.joinToString(", ") { "\"$it\"" })
            if (unexpected.isNotEmpty()) add("unexpected " + unexpected.joinToString(", ") { "\"$it\"" })
        }
        return "args fields do not match the descriptor: " + clauses.joinToString("; ")
    }

    /**
     * Broadcasts a `server-request` frame to every connected `/api/events.mux` socket.
     *
     * The [frame] argument is the frame payload. If it is a JSON object carrying a string
     * `method` member, that member becomes the frame method and its `payload` member (when
     * present) the frame payload; otherwise the whole [frame] becomes the payload and the
     * method defaults to `"frame"`. Every broadcast gets a fresh `rpcId`.
     */
    suspend fun pushMux(frame: JsonElement): String {
        val rpcId = broadcast(muxSockets, frame)
        rememberQuestion(rpcId, payloadOf(frame))
        return rpcId
    }

    /**
     * Remember a pushed question request, so an answer to it is held to the host's own standard
     * instead of being waved through. Everything else — an approval, a frame no test registered —
     * keeps the blanket acknowledgement.
     */
    private fun rememberQuestion(rpcId: String, payload: JsonElement) {
        val frame = payload as? JsonObject ?: return
        if ((frame["type"] as? JsonPrimitive)?.contentOrNull != "question/requested") return
        val sessionId = (frame["sessionId"] as? JsonPrimitive)?.contentOrNull ?: return
        val questions = frame["questions"] as? JsonArray ?: return
        pendingQuestions[rpcId] = PendingQuestion(sessionId, questions)
    }

    /** The receipt for one `/api/respond` body, as JSON. */
    private fun judgeRespond(body: String): String {
        val envelope = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return REFUSED_BAD_RESPONSE
        val rpcId = (envelope["rpcId"] as? JsonPrimitive)?.contentOrNull
        val pending = rpcId?.let { pendingQuestions[it] } ?: return ACCEPTED
        return when (val receipt = judgeQuestionResponse(envelope, pending)) {
            is QuestionReceipt.Accepted -> {
                pendingQuestions.remove(rpcId)
                ACCEPTED
            }
            is QuestionReceipt.Refused -> """{"accepted":false,"reason":"${receipt.reason}"}"""
        }
    }

    /**
     * Broadcasts a `server-request` frame to every connected `/api/events.host` socket;
     * see [pushMux] for the frame shape.
     */
    suspend fun pushHost(frame: JsonElement): String = broadcast(hostSockets, frame)

    /**
     * The session-log download: a plain `GET` answered with an attachment, not an RPC. Serves
     * whatever [sessionExportBytes] holds so a test can assert on the streamed content.
     */
    private suspend fun ApplicationCall.handleSessionExport() {
        if (!isTrustedHost(request.hostHeader())) {
            respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        val sessionId = request.queryParameters["sessionId"]
        if (sessionId.isNullOrBlank()) {
            respondText("missing sessionId", status = HttpStatusCode.BadRequest)
            return
        }
        response.header("Content-Disposition", "attachment; filename=\"dsh-session-$sessionId.zip\"")
        respondBytes(sessionExportBytes, ContentType.Application.Zip)
    }

    private suspend fun ApplicationCall.handleApi(pathMethodOverride: String? = null) {
        if (!isTrustedHost(request.hostHeader())) {
            respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        val pathMethod = pathMethodOverride ?: parameters["method"] ?: ""
        if (pathMethod == "respond") {
            respondJson(judgeRespond(runCatching { receiveText() }.getOrDefault("")))
            return
        }
        val body = runCatching { receiveText() }.getOrDefault("")
        val envelope = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val rpcId = (envelope?.get("rpcId") as? JsonPrimitive)?.contentOrNull
        val type = (envelope?.get("type") as? JsonPrimitive)?.contentOrNull
        if (rpcId == null || type != "client-request") {
            respondJson(errorEnvelope(rpcId.orEmpty(), "internal", "invalid client-request envelope"))
            return
        }
        val method = (envelope?.get("method") as? JsonPrimitive)?.contentOrNull ?: pathMethod
        val payload = envelope?.get("payload") ?: JsonNull
        when {
            asyncHandlers.containsKey(method) ->
                respondJson(okEnvelope(rpcId, asyncHandlers[method]!!(payload)))
            okHandlers.containsKey(method) -> try {
                respondJson(okEnvelope(rpcId, okHandlers[method]!!(payload)))
            } catch (invalid: ArgumentsInvalid) {
                // The gateway reports a refused args object as an ordinary thrown failure, which
                // the RPC layer renders with code `internal` — a shape mismatch does not get its
                // own error code, which is exactly why the client must not send one to find out.
                respondJson(errorEnvelope(rpcId, "internal", invalid.message))
            }
            failHandlers.containsKey(method) -> {
                val error = failHandlers[method]!!(payload)
                respondJson(errorEnvelope(rpcId, error.code, error.message, error.details))
            }
            else -> respondJson(errorEnvelope(rpcId, "internal", "unregistered $method"))
        }
    }

    private suspend fun WebSocketSession.handleMuxSocket() {
        muxSockets += this
        try {
            send(muxSubscribedHello())
            while (incoming.receiveCatching().isSuccess) {
                // The DSH protocol is downlink-only; frames from the client are discarded.
            }
        } finally {
            muxSockets -= this
        }
    }

    private suspend fun WebSocketSession.handleHostSocket() {
        hostSockets += this
        try {
            while (incoming.receiveCatching().isSuccess) {
                // The DSH protocol is downlink-only; frames from the client are discarded.
            }
        } finally {
            hostSockets -= this
        }
    }

    /** The frame's payload slot, or the frame itself when it carries no envelope of its own. */
    private fun payloadOf(frame: JsonElement): JsonElement =
        (frame as? JsonObject)?.get("payload") ?: frame

    /** Sends the frame to every listener and returns the rpcId it was minted with. */
    private suspend fun broadcast(sockets: MutableSet<WebSocketSession>, frame: JsonElement): String {
        val frameObject = frame as? JsonObject
        val method = (frameObject?.get("method") as? JsonPrimitive)?.contentOrNull ?: "frame"
        val rpcId = UUID.randomUUID().toString()
        val envelope = buildJsonObject {
            put("type", "server-request")
            put("rpcId", rpcId)
            put("method", method)
            put("payload", payloadOf(frame))
        }.toString()
        for (session in sockets) {
            try {
                session.send(envelope)
            } catch (ignored: Exception) {
                // A disconnected client must not abort the broadcast.
            }
        }
        return rpcId
    }

    private fun describeValue(): JsonObject {
        val base = buildJsonObject {
            put("version", "0.1.0-rc.8")
            put("cwd", "C:\\demo")
            put("attachedSessions", 0)
            put("home", "C:\\Users\\demo")
            put("canOpenPath", true)
        }
        return describeTransform?.invoke(base) ?: base
    }

    /**
     * The host's own attachment bounds, as the `imageLimits` session projection carries them.
     *
     * Values are the shipped harness defaults at 0.1.0-rc.8, including the 3.5MB per-image cap
     * that release lowered from 5MB and the per-side `maxImageDimension` it added.
     */
    fun imageLimitsValue(): JsonObject = buildJsonObject {
        put("maxImageBytes", 3_670_016)
        put("maxImagesPerMessage", 20)
        put("maxMessageImageBytes", 104_857_600)
        put("maxImagePixels", 40_000_000)
        put("maxImageDimension", 2_000)
        putJsonArray("mediaTypes") {
            add("image/png")
            add("image/jpeg")
            add("image/webp")
            add("image/gif")
        }
    }

    /** Broadcast one `session/projection` frame, the way the host publishes a projection value. */
    suspend fun pushProjection(
        sessionId: String,
        key: String,
        value: JsonElement,
        seq: Int = 0,
    ): String = pushMux(
        buildJsonObject {
            put("type", "session/projection")
            put("sessionId", sessionId)
            put("key", key)
            put("value", value)
            put("seq", seq)
        },
    )

    /**
     * A small but representative plugin inventory.
     *
     * Enough shape to exercise the settings section against the mock: a scoped host module, a
     * client one, a `cordis:` core row whose short name is not derived from a `dsh-` prefix, and a
     * disabled row — which the real host sends with no `fiberPhase` at all, because a plugin the
     * composition switched off never enters the loader's lifecycle.
     */
    private fun pluginInventoryValue(): JsonObject = buildJsonObject {
        putJsonArray("entries") {
            addJsonObject {
                put("entryId", "1")
                put("moduleName", "@deepseek-ai/dsh-host-plugin-inventory")
                put("enabled", true)
                put("fiberPhase", "active")
            }
            addJsonObject {
                put("entryId", "2")
                put("moduleName", "@deepseek-ai/dsh-client-ui-plan")
                put("enabled", true)
                put("fiberPhase", "active")
            }
            addJsonObject {
                put("entryId", "3")
                put("moduleName", "cordis:timer")
                put("enabled", true)
                put("fiberPhase", "pending")
            }
            addJsonObject {
                put("entryId", "4")
                put("moduleName", "@deepseek-ai/dsh-tool-bash")
                put("enabled", false)
            }
        }
    }

    private fun muxSubscribedHello(): String = buildJsonObject {
        put("type", "server-request")
        put("rpcId", UUID.randomUUID().toString())
        put("method", "session/subscribed")
        put(
            "payload",
            buildJsonObject {
                put("sessionId", "demo")
                put("lastSeq", -1)
            },
        )
    }.toString()

    private fun ApplicationRequest.hostHeader(): String =
        headers["Host"] ?: host()

    private fun isTrustedHost(rawHost: String): Boolean {
        val normalized = normalizeHost(rawHost)
        return normalized in LOOPBACK_HOSTS || normalized in normalizedTrustedHosts
    }

    private companion object {
        val LOOPBACK_HOSTS: Set<String> =
            setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

        /** Normalizes a raw Host header value to a bare hostname/IP. */
        fun normalizeHost(raw: String): String {
            var value = raw.trim().lowercase()
            value = value.removePrefix("http://").removePrefix("https://")
            val at = value.lastIndexOf('@')
            if (at >= 0) {
                value = value.substring(at + 1)
            }
            return if (value.startsWith("[")) {
                val close = value.indexOf(']')
                if (close >= 0) value.substring(1, close) else value
            } else {
                val colon = value.indexOf(':')
                if (colon >= 0) value.substring(0, colon) else value
            }.trim()
        }
    }
}

private const val ACCEPTED = """{"accepted":true}"""
private const val REFUSED_BAD_RESPONSE = """{"accepted":false,"reason":"bad-response"}"""

private suspend fun ApplicationCall.respondJson(json: String) {
    respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
}

private fun okEnvelope(rpcId: String, value: JsonElement): String = buildJsonObject {
    put("type", "server-response")
    put("rpcId", rpcId)
    put(
        "result",
        buildJsonObject {
            put("ok", true)
            put("value", value)
        },
    )
}.toString()

private fun errorEnvelope(
    rpcId: String,
    code: String,
    message: String,
    details: JsonObject = buildJsonObject { },
): String = buildJsonObject {
    put("type", "server-response")
    put("rpcId", rpcId)
    put(
        "result",
        buildJsonObject {
            put("ok", false)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    put("details", details)
                },
            )
        },
    )
}.toString()
