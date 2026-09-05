package com.labteto.dshmobile.mockharness

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.request.host
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
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
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scriptable stand-in for the DeepSeek Harness HTTP/WebSocket protocol.
 *
 * Speaks harness **0.1.3-alpha.1**:
 *  - unary calls: `POST /api/<namespace>/<method>` with a `client-request` envelope, answered
 *    with a `server-response` envelope carrying `{"ok": true, "value": ...}` or
 *    `{"ok": false, "error": {"code", "message", "details"}}`, where gateway refusals carry
 *    their 0.1.3 namespaced codes (`gateway/arguments-invalid`, `gateway/input-invalid`, …);
 *  - one bidirectional WebSocket at `/api/remote.mux`, carrying logical streams the client
 *    opens by name. The `$events` stream answers its `open` with a `ready` frame; everything
 *    else a test pushes rides whichever stream it names, including the `assistant-stream`
 *    frames a `session/follow` follower opted into ([pushAssistantStream]);
 *  - answers to pending waterfalls: `POST /api/$events/result` with `{clientId, eventId,
 *    outcome}`. An answer to a question this mock pushed is judged by [judgeQuestionResponse],
 *    the host's own acceptance law; anything else is acknowledged;
 *  - file uploads: the raw-byte route `POST /api/session/uploadFileBinary` and the
 *    `fileUploads/upload` Remote, both minting receipts a prompt can cite.
 *
 * `/api/events.mux`, `/api/events.host`, `/api/respond` and `host.describe` are all gone, as
 * they are upstream.
 *
 * A trust fence rejects every POST whose Host header is neither loopback nor listed in
 * [trustedHosts] with HTTP 403, replicated before any dispatch.
 */
class MockHarness(
    private val trustedHosts: List<String> = emptyList(),
    private val port: Int = 0,
    private val relay: RelayMode? = null,
    private val relayRedirectTo: String? = null,
) {
    /** Consumed once the pairing code is claimed, so a replayed code is refused as the relay does. */
    @Volatile
    private var pairingClaimed: Boolean = false

    /** Set by a test to make the next claim answer 429 instead of enrolling. */
    @Volatile
    var pairingRateLimited: Boolean = false

    private val okHandlers = ConcurrentHashMap<String, (JsonElement) -> JsonElement>()
    private val failHandlers = ConcurrentHashMap<String, (JsonElement) -> RpcErrorData>()
    private val asyncHandlers = ConcurrentHashMap<String, suspend (JsonElement) -> JsonElement>()
    private val pendingQuestions = ConcurrentHashMap<String, PendingQuestion>()

    /**
     * Every connected mux socket and the logical streams open on it.
     *
     * One socket carries them all, so a push has to know which stream a frame belongs to —
     * which is the substantive difference from the two fixed downlinks this replaces.
     */
    private val muxSockets = ConcurrentHashMap<WebSocketSession, ConcurrentHashMap<String, String>>()

    /** The client id handed out with the `ready` frame; every answer must carry it back. */
    val clientId: String = "mock-client"

    @Volatile
    private var readyHostTransform: ((JsonObject) -> JsonObject)? = null

    /** Body served by `GET /api/session.export`; tests set this to assert on the streamed bytes. */
    @Volatile
    var sessionExportBytes: ByteArray = ByteArray(0)

    /**
     * Every `request` object this harness accepted for `session/prompt`, in arrival order.
     *
     * Kept so a test can assert on what the client actually put on the wire — the identity it
     * minted above all — rather than only on the call having succeeded.
     */
    val sessionPrompts: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** Every `request` object this harness accepted for `subagents/prompt`, in arrival order. */
    val subagentPrompts: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** Every file staged through either upload path, in arrival order. */
    val fileUploads: MutableList<FileUploadRecord> = CopyOnWriteArrayList()

    /**
     * Answer the raw-byte upload route with 404, as a deployment that composes no file-upload
     * service or a relay that does not proxy the route would. The Remote form stays registered,
     * which is exactly the situation the client's fallback exists for.
     */
    @Volatile
    var refuseBinaryUploads: Boolean = false

    private val normalizedTrustedHosts: Set<String> =
        trustedHosts.mapTo(mutableSetOf()) { normalizeHost(it) }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    init {
        // The probe every reachability check makes: no arguments, and every deployment composes
        // the Session Controller that serves it.
        on("session/canOpenWorkspacePath") { JsonPrimitive(true) }
        on("pluginInventory/list") { pluginInventoryValue() }
        // Registered here rather than left to each test, because the point of holding these two to
        // the host's required fields is to catch a client that stopped sending one — and a check
        // only the tests that remember to ask for it get is a check that was not there when it
        // mattered. A test that wants a different answer re-registers the endpoint as usual.
        requestRemote(
            "session",
            "prompt",
            required = setOf("requestId", "sessionId", "mode", "content"),
            optional = setOf("clientTimeZone"),
        ) { request ->
            sessionPrompts.add(request)
            buildJsonObject { put("accepted", true) }
        }
        requestRemote(
            "subagents",
            "prompt",
            required = setOf("requestId", "parentSessionId", "childSessionId", "mode", "content"),
            optional = setOf("clientTimeZone"),
        ) { request ->
            subagentPrompts.add(request)
            buildJsonObject { put("messageId", UUID.randomUUID().toString()) }
        }
        // The Remote form of a file upload (harness 0.1.3): agent-scoped, so `agentId` beside a
        // request object whose only required field is the canonical base64. The raw-byte route
        // below is the ordinary path; this one is what a client falls back to when nothing
        // claims that route.
        remote("fileUploads", "upload", setOf("agentId", "request")) { args ->
            val request = args["request"] as? JsonObject
                ?: throw BoundaryInvalid(boundaryInvalidMessage("fileUploads/upload", "request"))
            val data = (request["data"] as? JsonPrimitive)?.contentOrNull
                ?: throw BoundaryInvalid(boundaryInvalidMessage("fileUploads/upload", "request"))
            val bytes = runCatching { Base64.getDecoder().decode(data) }.getOrElse {
                throw BoundaryInvalid(boundaryInvalidMessage("fileUploads/upload", "request"))
            }
            stageFile(
                sessionId = (args["agentId"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                name = (request["name"] as? JsonPrimitive)?.contentOrNull,
                bytes = bytes,
            )
        }
    }

    /**
     * Stage one file the way the host's `fileUploads` service does: store the bytes verbatim,
     * name them by digest, and mint a receipt only this session may cite.
     */
    private fun stageFile(sessionId: String, name: String?, bytes: ByteArray): JsonObject {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() } ?: "upload"
        val record = FileUploadRecord(
            sessionId = sessionId,
            receiptId = "receipt-${fileUploads.size + 1}",
            name = leaf,
            bytes = bytes,
        )
        fileUploads.add(record)
        return buildJsonObject {
            put("receiptId", record.receiptId)
            put(
                "file",
                buildJsonObject {
                    put("attachmentId", "sha256:$digest")
                    put("name", leaf)
                    put("bytes", bytes.size)
                },
            )
        }
    }

    /**
     * The raw-byte upload route (`POST /api/session/uploadFileBinary`), as the host's
     * `handleFileUploadHttp` answers it: carrier misuse gets a plain status, and everything past
     * that is a bare `{ok, value|error}` result under HTTP 200 — not a `server-response` envelope.
     */
    private suspend fun ApplicationCall.handleFileUpload() {
        if (!isTrustedHost(request.hostHeader())) {
            respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        if (refuseBinaryUploads) {
            respondText("not found", status = HttpStatusCode.NotFound)
            return
        }
        val mediaType = (request.headers["Content-Type"] ?: "").substringBefore(';').trim().lowercase()
        if (mediaType != "application/octet-stream") {
            respondText("content type must be application/octet-stream", status = HttpStatusCode.UnsupportedMediaType)
            return
        }
        val sessionId = request.queryParameters["sessionId"]
        if (sessionId.isNullOrEmpty()) {
            respondText("sessionId is required", status = HttpStatusCode.BadRequest)
            return
        }
        val bytes = receive<ByteArray>()
        val value = stageFile(sessionId, request.queryParameters["name"], bytes)
        respondJson(
            buildJsonObject {
                put("ok", true)
                put("value", value)
            }.toString(),
        )
    }

    /**
     * Starts the server on [port] (0 lets the OS assign one) and returns the bound port.
     */
    suspend fun start(): Int {
        val newServer = embeddedServer(Netty, port = port, host = "127.0.0.1") {
            install(WebSockets)
            // The relay's credential check, in the one place that also covers a WebSocket upgrade.
            // Intercepting before routing is what makes an unauthenticated upgrade fail at the
            // handshake with a plain 403 rather than opening and then closing — which is the
            // difference the client actually has to handle, since a rejected upgrade carries its
            // status and nothing else.
            if (relay != null) {
                intercept(ApplicationCallPipeline.Plugins) {
                    val inbound = context
                    val path = inbound.request.path()
                    val authorized = inbound.request.headers["Authorization"] == "Bearer ${relay.token}"
                    if (!path.startsWith("/relay/") && !authorized) {
                        inbound.respondText(
                            """{"error":"forbidden","message":"pair this device with the relay first"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Forbidden,
                        )
                        finish()
                    }
                }
            }
            routing {
                // The harness's own port since relay 0.1.1: nothing else claims `/relay`, so the
                // plugin registers a prefix route that redirects to its listener rather than
                // letting the single-page application's catch-all answer.
                if (relayRedirectTo != null) {
                    // A harness in front of a relay, not a relay: it owns none of these paths and
                    // says so by pointing at the listener that does. Registering the relay's own
                    // routes as well would out-specific this tailcard and answer for it.
                    get("/relay/{...}") {
                        call.redirectToRelay(relayRedirectTo)
                    }
                    post("/relay/{...}") {
                        call.redirectToRelay(relayRedirectTo)
                    }
                } else {
                    get("/relay/health") {
                        if (relay?.refuseHost == true) {
                            call.refuseFence()
                            return@get
                        }
                        if (relay == null) {
                            call.respondText("not found", status = HttpStatusCode.NotFound)
                        } else {
                            call.respondText(
                                """{"service":"dsh-relay","ok":true}""",
                                ContentType.Application.Json,
                            )
                        }
                    }
                    post("/relay/pair") {
                        if (relay?.refuseHost == true) {
                            call.refuseFence()
                            return@post
                        }
                        call.handleRelayPair()
                    }
                }
                get("/api/session.export") {
                    call.handleSessionExport()
                }
                // A literal path wins over the `{namespace}/{method}` pattern below, exactly as
                // the host's fetch registry answers this route before the RPC dispatcher does.
                post(FILE_UPLOAD_PATH) {
                    call.handleFileUpload()
                }
                // Kept for the Gateway's own single-segment endpoints; every business call
                // takes the two-segment form below.
                post("/api/{method}") {
                    call.handleApi()
                }
                post("/api/{namespace}/{method}") {
                    val namespace = call.parameters["namespace"].orEmpty()
                    val method = call.parameters["method"].orEmpty()
                    call.handleApi("$namespace/$method")
                }
                webSocket("/api/remote.mux") {
                    handleMuxSocket()
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
     * Overrides the host facts served in the `$events` ready frame.
     *
     * Replaces the old `describe { … }` hook. There is far less to override now: the ready frame
     * carries only `home`, because 0.1.2 publishes no version, cwd or attached-session count at
     * all.
     */
    fun readyHost(transform: (JsonObject) -> JsonObject) {
        readyHostTransform = transform
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
                throw ArgumentsInvalid(argumentsInvalidMessage(endpoint, missing, unexpected))
            }
        }
    }

    /**
     * Registers a remote whose sole argument is `request`, held to the gateway's *boundary* rule.
     *
     * [remote] matches the args keys, which is one layer too shallow for an endpoint that takes a
     * single request object: every such call passes `args` key matching and is then decoded
     * against a strict codec, so a field missing from inside `request` is refused separately and
     * with a different message. A mock that checked only the outer key set would happily accept a
     * prompt the real host rejects, which is the exact gap that let a prompt without `requestId`
     * ship green.
     *
     * Only [required] absences are refused. The codec is a zod object, which drops a key it does
     * not declare rather than failing on it, so an unexpected field is not an error here the way
     * an unexpected *arg* is.
     *
     * @param required every field the host declares without `?`.
     * @param optional every field it declares with one; listed for the reader, not enforced.
     * @param handler maps the validated request object to the remote's return value.
     */
    fun requestRemote(
        namespace: String,
        method: String,
        required: Set<String>,
        optional: Set<String> = emptySet(),
        handler: (JsonObject) -> JsonElement,
    ) {
        val endpoint = "$namespace/$method"
        require(optional.none { it in required }) {
            "$endpoint: a field cannot be both required and optional"
        }
        remote(namespace, method, setOf("request")) { args ->
            val request = args["request"] as? JsonObject
                ?: throw BoundaryInvalid(boundaryInvalidMessage(endpoint, "request"))
            // The refusal names the field that failed and nothing inside it: the gateway keeps
            // boundary values out of its own message, so a mock that listed the missing keys
            // would be handing the client a hint the real host withholds.
            if (required.any { it !in request.keys }) {
                throw BoundaryInvalid(boundaryInvalidMessage(endpoint, "request"))
            }
            handler(request)
        }
    }

    /** The gateway's `gateway/arguments-invalid` refusal, thrown out of a [remote] handler. */
    class ArgumentsInvalid(override val message: String) : RuntimeException(message)

    /**
     * The gateway's `gateway/input-invalid` refusal, thrown out of a [requestRemote] handler.
     *
     * Through 0.1.2 every gateway failure reached the client as code `internal`; 0.1.3 gave each
     * its own namespaced code. The message still names only the field, never what was wrong
     * inside it, which is precisely why a client must not send a bad shape to find out.
     */
    class BoundaryInvalid(override val message: String) : RuntimeException(message)

    private fun boundaryInvalidMessage(endpoint: String, field: String): String =
        "typert gateway: $endpoint: wire field \"$field\" failed boundary validation"

    private fun argumentsInvalidMessage(
        endpoint: String,
        missing: List<String>,
        unexpected: List<String>,
    ): String {
        val clauses = buildList {
            if (missing.isNotEmpty()) add("missing " + missing.joinToString(", ") { "\"$it\"" })
            if (unexpected.isNotEmpty()) add("unexpected " + unexpected.joinToString(", ") { "\"$it\"" })
        }
        return "typert gateway: $endpoint: args fields do not match the descriptor: " +
            clauses.joinToString("; ")
    }

    /**
     * Push one process-local assistant frame onto every open `session/follow` stream.
     *
     * [frame] is the inner frame — `start`, `chunk` or `end` — and is wrapped the way the host
     * wraps it, under `type: "assistant-stream"`, so a test asserts on the same shape the client
     * decodes from a real harness.
     */
    suspend fun pushAssistantStream(frame: JsonObject) {
        pushStream(
            SESSION_FOLLOW_ENDPOINT,
            buildJsonObject {
                put("type", "assistant-stream")
                put("frame", frame)
            },
        )
    }

    /**
     * Push one item onto every open `$events` stream.
     *
     * [frame] is the item verbatim — an `emit`, a `waterfall`, or a `cancel`. A pushed
     * `waterfall` naming the question event is remembered so its answer is held to the host's
     * own standard rather than waved through.
     */
    suspend fun pushEvent(frame: JsonElement): String {
        val eventId = ((frame as? JsonObject)?.get("eventId") as? JsonPrimitive)?.contentOrNull
            ?: UUID.randomUUID().toString()
        rememberQuestion(eventId, frame)
        pushStream(EVENTS_ENDPOINT, frame)
        return eventId
    }

    /**
     * Push one item onto every open stream for [endpoint].
     *
     * A test opens `session/follow` or `session/control` through the client and then feeds it
     * from here; an endpoint nobody opened simply has no listeners.
     */
    suspend fun pushStream(endpoint: String, item: JsonElement) {
        for ((session, streams) in muxSockets) {
            for ((streamId, openEndpoint) in streams) {
                if (openEndpoint != endpoint) continue
                try {
                    session.send(streamItem(streamId, item))
                } catch (ignored: Exception) {
                    // A disconnected client must not abort the broadcast.
                }
            }
        }
    }

    /** End one logical stream with an error, which is terminal for that stream alone. */
    suspend fun failStream(endpoint: String, code: String, message: String) {
        for ((session, streams) in muxSockets) {
            for ((streamId, openEndpoint) in streams.toMap()) {
                if (openEndpoint != endpoint) continue
                streams.remove(streamId)
                val frame = buildJsonObject {
                    put("type", "error")
                    put("streamId", streamId)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", code)
                            put("message", message)
                            put("details", buildJsonObject { })
                        },
                    )
                }
                runCatching { session.send(frame.toString()) }
            }
        }
    }

    /**
     * Remember a pushed question waterfall, so an answer to it is held to the host's own
     * standard instead of being waved through. Everything else — an approval, an event no test
     * registered — keeps the blanket acknowledgement.
     */
    private fun rememberQuestion(eventId: String, frame: JsonElement) {
        val f = frame as? JsonObject ?: return
        if ((f["type"] as? JsonPrimitive)?.contentOrNull != "waterfall") return
        if ((f["event"] as? JsonPrimitive)?.contentOrNull != "user-questions/request") return
        val sessionId = (f["agentId"] as? JsonPrimitive)?.contentOrNull ?: return
        val questions = (f["request"] as? JsonObject)?.get("questions") as? JsonArray ?: return
        pendingQuestions[eventId] = PendingQuestion(sessionId, questions)
    }

    /**
     * Judge one `$events/result` payload.
     *
     * The generation binding is real: an answer carrying a `clientId` this mock never issued is
     * refused, which is what stops a reply from a retired connection resolving a request the
     * host has since replayed.
     */
    private fun judgeEventResult(payload: JsonElement): JsonElement {
        val body = payload as? JsonObject ?: throw MockRefusal("bad-response")
        if ((body["clientId"] as? JsonPrimitive)?.contentOrNull != clientId) {
            throw MockRefusal("stale-generation")
        }
        val eventId = (body["eventId"] as? JsonPrimitive)?.contentOrNull
            ?: throw MockRefusal("bad-response")
        val pending = pendingQuestions[eventId] ?: return buildJsonObject { }
        val outcome = body["outcome"] as? JsonObject ?: throw MockRefusal("bad-response")
        when ((outcome["kind"] as? JsonPrimitive)?.contentOrNull) {
            // A dismissal, or a delegation. The host settles the tool call itself; the answer
            // law does not apply to either.
            "rejected", "next" -> {
                pendingQuestions.remove(eventId)
                return buildJsonObject { }
            }
            "result" -> Unit
            else -> throw MockRefusal("bad-response")
        }
        // The answer object is the outcome's value; 0.1.1 wrapped it in a response envelope,
        // and [judgeQuestionResponse] still reads that shape.
        val envelope = buildJsonObject {
            put(
                "result",
                buildJsonObject {
                    put("ok", true)
                    put("value", outcome["value"] ?: JsonNull)
                },
            )
        }
        return when (val receipt = judgeQuestionResponse(envelope, pending)) {
            is QuestionReceipt.Accepted -> {
                pendingQuestions.remove(eventId)
                buildJsonObject { }
            }
            is QuestionReceipt.Refused -> throw MockRefusal(receipt.reason)
        }
    }

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

    /**
     * Answer as the relay's DNS-rebinding fence does: plain text, before any route is reached.
     *
     * Plain text is the whole distinction a client has to work from. The relay uses the same 403
     * for a code it will not accept, but that one carries `{"error":"pairing-failed"}` — so a body
     * that is not that JSON is the fence, and the fix is on the relay's configuration rather than
     * on the pairing page.
     */
    private suspend fun ApplicationCall.refuseFence() {
        respondText("forbidden", status = HttpStatusCode.Forbidden)
    }

    /** Answer as the harness's `/relay` prefix route does: 302 to the relay, path and query kept. */
    private suspend fun ApplicationCall.redirectToRelay(target: String) {
        val query = request.queryString().takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
        response.header("Location", "$target${request.path()}$query")
        respondText("", status = HttpStatusCode.Found)
    }

    /**
     * The relay's claim endpoint.
     *
     * Answers JSON only when the caller asked for it — the real relay serves an HTML page
     * otherwise, and a client that forgets the content type finds that out by getting markup where
     * it expected a token. Reproducing that here is the point: it is the easiest thing to get wrong
     * and the hardest to notice.
     */
    private suspend fun ApplicationCall.handleRelayPair() {
        val mode = relay
        if (mode == null) {
            respondText("not found", status = HttpStatusCode.NotFound)
            return
        }
        val wantsJson = (request.headers["Content-Type"] ?: "").contains("application/json") ||
            ((request.headers["Accept"] ?: "").contains("application/json") &&
                !(request.headers["Accept"] ?: "").contains("text/html"))
        if (!wantsJson) {
            respondText("<html><body>pair</body></html>", ContentType.Text.Html)
            return
        }
        if (pairingRateLimited) {
            response.header("Retry-After", mode.retryAfterSeconds.toString())
            respondText(
                """{"error":"rate-limited"}""",
                ContentType.Application.Json,
                HttpStatusCode.TooManyRequests,
            )
            return
        }
        val body = runCatching { receiveText() }.getOrDefault("")
        val fields = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val code = (fields?.get("code") as? JsonPrimitive)?.contentOrNull
        if (code != mode.pairingCode || pairingClaimed) {
            respondText(
                """{"error":"pairing-failed","message":"That code is not valid."}""",
                ContentType.Application.Json,
                HttpStatusCode.Forbidden,
            )
            return
        }
        pairingClaimed = true
        val answer = buildJsonObject {
            put("deviceId", mode.deviceId)
            put("token", mode.token)
            put("expiresAt", mode.tokenExpiresAt)
            if (mode.fingerprint != null) put("fingerprint", mode.fingerprint)
        }
        respondText(answer.toString(), ContentType.Application.Json)
    }

    private suspend fun ApplicationCall.handleApi(pathMethodOverride: String? = null) {
        if (!isTrustedHost(request.hostHeader())) {
            respondText("Forbidden", status = HttpStatusCode.Forbidden)
            return
        }
        val pathMethod = pathMethodOverride ?: parameters["method"] ?: ""
        val body = runCatching { receiveText() }.getOrDefault("")
        val envelope = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val rpcId = (envelope?.get("rpcId") as? JsonPrimitive)?.contentOrNull
        val type = (envelope?.get("type") as? JsonPrimitive)?.contentOrNull
        if (rpcId == null || type != "client-request") {
            respondJson(errorEnvelope(rpcId.orEmpty(), "gateway/bad-request", "invalid client-request message"))
            return
        }
        val method = (envelope?.get("method") as? JsonPrimitive)?.contentOrNull ?: pathMethod
        val payload = envelope?.get("payload") ?: JsonNull
        if (method == EVENT_RESULT_ENDPOINT) {
            try {
                respondJson(okEnvelope(rpcId, judgeEventResult(payload)))
            } catch (refusal: MockRefusal) {
                respondJson(errorEnvelope(rpcId, refusal.reason, "answer refused"))
            }
            return
        }
        when {
            asyncHandlers.containsKey(method) ->
                respondJson(okEnvelope(rpcId, asyncHandlers[method]!!(payload)))
            okHandlers.containsKey(method) -> try {
                respondJson(okEnvelope(rpcId, okHandlers[method]!!(payload)))
            } catch (invalid: ArgumentsInvalid) {
                // Since 0.1.3 every gateway refusal carries its own namespaced code; the
                // messages are unchanged, and still name the field rather than the fault.
                respondJson(errorEnvelope(rpcId, "gateway/arguments-invalid", invalid.message))
            } catch (invalid: BoundaryInvalid) {
                respondJson(errorEnvelope(rpcId, "gateway/input-invalid", invalid.message))
            }
            failHandlers.containsKey(method) -> {
                val error = failHandlers[method]!!(payload)
                respondJson(errorEnvelope(rpcId, error.code, error.message, error.details))
            }
            else -> respondJson(errorEnvelope(rpcId, "gateway/internal", "unregistered $method"))
        }
    }

    /**
     * The mux: one socket, many logical streams.
     *
     * Unlike the downlinks it replaces, this reads from the client — a stream exists only
     * because the client asked for it, so a mock that ignored incoming messages would answer
     * nothing at all.
     */
    private suspend fun WebSocketSession.handleMuxSocket() {
        val streams = ConcurrentHashMap<String, String>()
        muxSockets[this] = streams
        try {
            while (true) {
                val received = incoming.receiveCatching().getOrNull() ?: break
                val text = (received as? Frame.Text)?.readText() ?: continue
                val message = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                    ?: continue
                val streamId = (message["streamId"] as? JsonPrimitive)?.contentOrNull ?: continue
                when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
                    "open" -> {
                        val endpoint = (message["endpoint"] as? JsonPrimitive)?.contentOrNull
                            ?: continue
                        streams[streamId] = endpoint
                        // `$events` proves readiness by answering immediately; every other
                        // stream stays silent until a test pushes to it.
                        if (endpoint == EVENTS_ENDPOINT) send(streamItem(streamId, readyFrame()))
                    }
                    "cancel" -> {
                        streams.remove(streamId)
                        send(
                            buildJsonObject {
                                put("type", "end")
                                put("streamId", streamId)
                            }.toString(),
                        )
                    }
                }
            }
        } finally {
            muxSockets.remove(this)
        }
    }

    /** One `item` frame for a logical stream. */
    private fun streamItem(streamId: String, value: JsonElement): String = buildJsonObject {
        put("type", "item")
        put("streamId", streamId)
        put("value", value)
    }.toString()

    /**
     * The opening frame of `$events`: what makes a connection generation ready.
     *
     * It carries the only host fact 0.1.2 publishes plus the client id every later answer must
     * quote — which is what `host.describe` used to be for.
     */
    private fun readyFrame(): JsonObject {
        val host = buildJsonObject { put("home", "C:\\Users\\demo") }
        return buildJsonObject {
            put("type", "ready")
            put("clientId", clientId)
            put("host", readyHostTransform?.invoke(host) ?: host)
        }
    }

    /**
     * The host's own attachment bounds, as the `imageLimits` session projection carries them.
     *
     * Values are the shipped harness defaults at 0.1.1-rc.2, which raised every admission cap
     * (per-image bytes, per-message bytes, pixels, per-side dimension) once the host began
     * normalizing stored images after admission.
     */
    fun imageLimitsValue(): JsonObject = buildJsonObject {
        put("maxImageBytes", 20_971_520)
        put("maxImagesPerMessage", 20)
        put("maxMessageImageBytes", 209_715_200)
        put("maxImagePixels", 64_000_000)
        put("maxImageDimension", 8_192)
        putJsonArray("mediaTypes") {
            add("image/png")
            add("image/jpeg")
            add("image/webp")
            add("image/gif")
        }
    }

    /**
     * Push one projection value the way the host publishes one.
     *
     * It rides `session/control` now rather than the all-session mux — the stream that also
     * carries queue and job state for every live session.
     */
    suspend fun pushProjection(
        sessionId: String,
        key: String,
        value: JsonElement,
        seq: Int = 0,
    ) = pushStream(
        SESSION_CONTROL_ENDPOINT,
        buildJsonObject {
            put("type", "projection")
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

/** The Gateway-internal stream every connection opens to learn it is ready. */
internal const val EVENTS_ENDPOINT = "\u0024events"

/** The host-wide live-control stream: queue, jobs and projections for every live session. */
internal const val SESSION_CONTROL_ENDPOINT = "session/control"

/** One session's journal: a complete opening snapshot, then live events. */
internal const val SESSION_FOLLOW_ENDPOINT = "session/follow"

/** The Gateway-internal unary endpoint one waterfall answer is posted to. */
internal const val EVENT_RESULT_ENDPOINT = "\u0024events/result"

/**
 * A refused answer.
 *
 * 0.1.1 answered a bad response with an `{"accepted":false,"reason":…}` receipt on its own
 * carrier. 0.1.2 has no such carrier — the answer is an ordinary unary call — so a refusal is an
 * ordinary business error whose `code` carries the reason.
 */
internal class MockRefusal(val reason: String) : Exception(reason)

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

/**
 * Turns the mock into a `dsh-relay` stand-in.
 *
 * The relay is a second listener in front of the harness, not a different protocol: everything under
 * `/api` is forwarded unchanged once the credential checks out. So this is deliberately thin — a
 * bearer requirement, the claim endpoint, and the liveness probe — and every existing test of the
 * wire protocol keeps applying behind it.
 */
/**
 * Where the harness's own port sends `/relay` traffic.
 *
 * Pass a relay origin to [MockHarness] as `relayRedirectTo` to model the prefix route the plugin
 * registers on `ctx.webServer` — the thing that makes the harness address a usable way in, and the
 * thing a client must resolve rather than let its HTTP layer chase.
 */
data class RelayMode(
    /** The single-use code the operator would read off the relay's pairing page. */
    val pairingCode: String = "48213977",
    /** The bearer token a successful claim mints. */
    val token: String = "relay-test-token",
    val deviceId: String = "9f2c41ab30d7e155",
    /** Repeated in the claim answer, as a TLS relay does; null stands in for `tls: off`. */
    val fingerprint: String? = null,
    val tokenExpiresAt: Long = 1_900_000_000_000,
    /** What a rate-limited claim reports in `Retry-After`. */
    val retryAfterSeconds: Long = 30,
    /**
     * Refuse every `/relay` request the way the fence does.
     *
     * Stands in for an address the relay does not know itself by — an emulator's host alias, a
     * name it was never told. Its fence runs before every route, so even the unauthenticated
     * liveness probe gets this, and a client that reads it as "nothing there" reports a working
     * relay as a missing one.
     */
    val refuseHost: Boolean = false,
)

/** The raw-byte file-upload route harness 0.1.3 registers beside the RPC dispatcher. */
internal const val FILE_UPLOAD_PATH = "/api/session/uploadFileBinary"

/** One file staged by either upload path, kept so a test can assert on what arrived. */
data class FileUploadRecord(
    val sessionId: String,
    val receiptId: String,
    val name: String,
    val bytes: ByteArray,
)
