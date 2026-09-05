package com.labteto.dshmobile.core.wire

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink

/**
 * One HTTP carrier exchange: the status code and raw body of a POST /api request.
 * HTTP status is carrier-only — business errors arrive as HTTP 200 with `ok: false` in the body.
 */
data class RpcHttpResponse(
    val status: Int,
    val body: String,
)

/** Thrown by [RpcTransport] on a non-2xx carrier response or a network-level failure. */
class RpcTransportException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The HTTP carrier for unary RPCs: one `POST /api/<path>` exchange. */
interface RpcTransport {
    /**
     * POST [body] to [path] (e.g. "/api/session.list"). Returns the carrier response, or throws
     * [RpcTransportException] on a non-2xx status or transport failure.
     */
    suspend fun post(path: String, body: String): RpcHttpResponse

    /**
     * GET a binary download from [path] (e.g. "/api/session.export?sessionId=…"), the harness's
     * one non-envelope read channel.
     *
     * [consume] runs on an IO thread with the response still open and must not retain the stream —
     * the connection is released as soon as it returns. Throws [RpcTransportException] on a non-2xx
     * status or transport failure.
     */
    suspend fun <T> download(
        path: String,
        consume: (contentType: String?, contentDisposition: String?, body: InputStream) -> T,
    ): T

    /**
     * POST raw bytes to [path] — the harness's one non-envelope *write* channel, the streaming
     * file-upload route harness 0.1.3 added (`/api/session/uploadFileBinary`).
     *
     * [body] is read once on an IO thread and must be positioned at the first byte to send;
     * [contentLength] is declared up front so the host can refuse an oversized upload before
     * reading it. [onProgress] is called with the running byte count as the body is written.
     * Returns the carrier response (the route answers 200 with a bare `{ok, value|error}`
     * result), or throws [RpcTransportException] on a non-2xx status or transport failure.
     */
    suspend fun upload(
        path: String,
        contentType: String,
        contentLength: Long,
        body: InputStream,
        onProgress: ((sent: Long) -> Unit)? = null,
    ): RpcHttpResponse
}

/** JSON media type used for every /api POST. */
private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

/**
 * OkHttp-backed [RpcTransport]. Sends `Content-Type: application/json`, sets the `Host` header
 * from the base URL, and times out at [connectTimeoutMs]/[readTimeoutMs] (30s by default). Non-2xx
 * responses throw [RpcTransportException] (403 mentions the harness trust fence).
 *
 * [baseUrl] may be `http://` or `https://`; the `Host` header omits the port when it is the
 * scheme's default, which is what a relay behind a name on :443 needs.
 *
 * The timeouts are constructor parameters rather than something a caller pre-applies to [client]:
 * this class rebuilds the client it is handed, so a builder-applied deadline was silently replaced
 * by the 30s default. That is why a discovery probe advertising a 700ms budget could block for
 * thirty seconds. [authorization] is a parameter for the same reason: an interceptor a caller
 * applied to [client] would survive the rebuild, but the two mechanisms would then disagree about
 * where request shaping lives, and the header has to be visible at the call site because a request
 * that silently loses it fails as a 403 that reads like a trust fence.
 */
class OkHttpRpcTransport(
    baseUrl: String,
    client: OkHttpClient = defaultClient(),
    connectTimeoutMs: Long = 30_000,
    readTimeoutMs: Long = 30_000,
    writeTimeoutMs: Long = 30_000,
    private val authorization: String? = null,
    private val cookie: String? = null,
) : RpcTransport {

    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val hostHeader: String = run {
        val defaultPort = when (base.scheme) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
        if (base.port == defaultPort) base.host else "${base.host}:${base.port}"
    }
    private val httpClient: OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun post(path: String, body: String): RpcHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val target = base.resolve(path)
                ?: throw RpcTransportException(0, "cannot resolve $path against $base")
            val request = Request.Builder()
                .url(target)
                .header("Host", hostHeader)
                .header("Content-Type", "application/json")
                .authorized(authorization)
                .cookied(cookie)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            RpcTransportException(0, "transport failure: ${e.message}", e),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        val responseBody = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            continuation.resume(RpcHttpResponse(resp.code, responseBody))
                        } else {
                            continuation.resumeWithException(
                                RpcTransportException(resp.code, carrierMessage(resp.code)),
                            )
                        }
                    }
                }
            })
        }

    /**
     * Downloads and uploads reuse the unary client's connection pool but relax the timeouts: a
     * session log ZIP is streamed and compressed on the fly, and a file upload is bounded by the
     * link rather than the host, so 30s is a false deadline on either.
     */
    private val downloadClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    override suspend fun <T> download(
        path: String,
        consume: (contentType: String?, contentDisposition: String?, body: InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        val target = base.resolve(path)
            ?: throw RpcTransportException(0, "cannot resolve $path against $base")
        val request = Request.Builder()
            .url(target)
            .header("Host", hostHeader)
            .authorized(authorization)
            .cookied(cookie)
            .get()
            .build()
        val response = try {
            downloadClient.newCall(request).execute()
        } catch (e: IOException) {
            throw RpcTransportException(0, "transport failure: ${e.message}", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) throw RpcTransportException(resp.code, carrierMessage(resp.code))
            val body = resp.body
                ?: throw RpcTransportException(resp.code, "download carried no body")
            consume(
                resp.header("Content-Type"),
                resp.header("Content-Disposition"),
                body.byteStream(),
            )
        }
    }

    override suspend fun upload(
        path: String,
        contentType: String,
        contentLength: Long,
        body: InputStream,
        onProgress: ((sent: Long) -> Unit)?,
    ): RpcHttpResponse = withContext(Dispatchers.IO) {
        val target = base.resolve(path)
            ?: throw RpcTransportException(0, "cannot resolve $path against $base")
        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType? = contentType.toMediaType()
            override fun contentLength(): Long = contentLength
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress?.invoke(sent)
                }
            }
        }
        val request = Request.Builder()
            .url(target)
            .header("Host", hostHeader)
            .authorized(authorization)
            .cookied(cookie)
            .post(requestBody)
            .build()
        val response = try {
            downloadClient.newCall(request).execute()
        } catch (e: IOException) {
            throw RpcTransportException(0, "transport failure: ${e.message}", e)
        }
        response.use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RpcTransportException(resp.code, carrierMessage(resp.code))
            RpcHttpResponse(resp.code, responseBody)
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Set `Authorization` when there is a credential, and leave the request untouched when there is not.
 *
 * File-level so the unary path, the download path and the WebSocket upgrade all attach the header
 * the same way. `dsh-relay` verifies the token on the upgrade as well as on `/api`, and an upgrade
 * sent without it is refused at the handshake — which the connection loop can only report as a
 * stream that would not open.
 */
internal fun Request.Builder.authorized(authorization: String?): Request.Builder =
    if (authorization == null) this else header("Authorization", authorization)

/**
 * Set `Cookie` when there is a harness browser session, and leave the request untouched when
 * there is not.
 *
 * Harness 0.1.2 authenticates the complete `/api` surface — every Remote call, the mux upgrade,
 * and the session-log download — against one signed cookie, and answers 401 without it. The
 * cookie is obtained once per authority by exchanging the launch token at `GET /?token=…`; see
 * `HarnessSession`. It is deliberately not an `Authorization` header, because the harness refuses
 * to read the token from one.
 *
 * Behind `dsh-relay` this stays null: the relay holds the harness session itself and injects it
 * upstream, and a phone has no business carrying the host's cookie across the network.
 */
internal fun Request.Builder.cookied(cookie: String?): Request.Builder =
    if (cookie == null) this else header("Cookie", cookie)

/**
 * Carrier-layer failure text; 403 names the trust fence because that is the usual cause.
 *
 * File-level so the WebSocket path can wrap a failed upgrade in the same shape as a failed POST —
 * a fence rejection of `/api/events.mux` is the same fact as one on `/api/host.describe`.
 */
internal fun carrierMessage(status: Int): String = when (status) {
    // Since 0.1.2 these are two different facts and the connect screen has to say which. 403 is
    // the Host/Origin fence, which runs first and is about *where* the request came from. 401 is
    // the browser session, which is about *who* is asking — a harness that would answer happily
    // if this client had exchanged a launch token. Collapsing them sends people to reconfigure a
    // firewall when they actually need to re-pair.
    401 -> "harness has no browser session for this client (HTTP 401)"
    403 -> "harness trust fence rejected the request (HTTP 403)"
    else -> "carrier returned HTTP $status"
}

/** Receives WebSocket carrier events; all callbacks may run on OkHttp's socket threads. */
interface WsChannelSink {
    /**
     * One complete text message, still unparsed.
     *
     * The carrier deliberately does not decode: harness 0.1.2 multiplexes every logical stream
     * over this one socket, so which parser applies is a function of the message, not the socket.
     * Parsing here would put the mux's dispatch table in the transport.
     */
    fun onMessage(text: String)

    /** The WebSocket handshake completed and the socket is ready. */
    fun onOpen()

    /**
     * The socket closed or failed. `cause` is non-null on a failure, null on a clean close.
     * Every logical stream riding this socket ends with it.
     */
    fun onClosed(cause: Throwable?)
}

/**
 * A bidirectional WebSocket to `/api/remote.mux`.
 *
 * Its predecessor was downlink-only: `/api/events.mux` and `/api/events.host` closed the socket
 * with 1008 on any client message. The 0.1.2 mux is the opposite — the client must send `open`
 * and `cancel` to get anything at all — so [send] is the substantive difference here, not an
 * extra.
 *
 * The host sends RFC 6455 Ping at `websocketHeartbeatIntervalMs` (30s by default) and OkHttp
 * answers Pong at the protocol layer, so idle liveness needs no application code and no
 * application-level heartbeat frame. There is no Pong deadline upstream; half-open detection is
 * left to TCP.
 *
 * [authorization] rides on the upgrade itself, which is the whole credential for this socket:
 * there is no later request to carry one. `dsh-relay` refuses an unauthenticated upgrade at the
 * handshake, and against a direct 0.1.2 harness the browser-session cookie is required here too —
 * a missing credential costs the entire connection generation rather than one call.
 */
open class WsChannel(
    private val url: String,
    private val client: OkHttpClient,
    private val sink: WsChannelSink,
    private val authorization: String? = null,
    private val cookie: String? = null,
) {
    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var started: Boolean = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sink.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            sink.onMessage(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sink.onClosed(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A rejected upgrade carries its status only on the response; OkHttp reports it as a
            // bare ProtocolException ("Expected HTTP 101 … was '403'"), which no caller can
            // classify without reading English. Re-wrap so a trust-fence rejection of the stream
            // reads the same as one on a POST — and so 401 (no browser session) stays tellable
            // apart from 403 (Host/Origin refused), which now mean different things.
            sink.onClosed(
                if (response != null) {
                    RpcTransportException(response.code, carrierMessage(response.code), t)
                } else {
                    t
                },
            )
        }
    }

    /** Perform the RFC 6455 handshake and begin reading messages. Idempotent. */
    open fun start() {
        if (started) return
        started = true
        val request = Request.Builder()
            .url(url)
            .authorized(authorization)
            .cookied(cookie)
            .build()
        webSocket = client.newWebSocket(request, listener)
    }

    /**
     * Queue one text message. Returns false when the socket is gone or its send buffer is full —
     * the caller must treat that as the stream having failed rather than retrying, because
     * OkHttp has already begun tearing the socket down by then.
     */
    open fun send(text: String): Boolean = webSocket?.send(text) ?: false

    /** Tear the socket down. Idempotent. */
    open fun close() {
        webSocket?.cancel()
        webSocket = null
    }
}
