package com.labteto.dshmobile.core.wire

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

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
     * Downloads reuse the unary client's connection pool but relax the read timeout: a session log
     * ZIP is streamed and compressed on the fly, so 30s is a false deadline on a long session.
     */
    private val downloadClient: OkHttpClient by lazy {
        httpClient.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()
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
 * Carrier-layer failure text; 403 names the trust fence because that is the usual cause.
 *
 * File-level so the WebSocket path can wrap a failed upgrade in the same shape as a failed POST —
 * a fence rejection of `/api/events.mux` is the same fact as one on `/api/host.describe`.
 */
internal fun carrierMessage(status: Int): String = if (status == 403) {
    "harness trust fence rejected the request (HTTP 403)"
} else {
    "carrier returned HTTP $status"
}

/** Receives downlink WebSocket events; all callbacks may run on OkHttp's socket threads. */
interface WsDownlinkSink {
    /** One parsed downstream frame (`type: server-request`). */
    fun onFrame(frame: ServerRequest)

    /** The WebSocket handshake completed and the socket is ready. */
    fun onOpen()

    /**
     * The socket closed or failed. `cause` is non-null on a failure, null on a clean close.
     * The loop treats this as the end of the stream's generation.
     */
    fun onClosed(cause: Throwable?)
}

/**
 * A downlink-only WebSocket to `/api/events.mux` or `/api/events.host`. The client never sends
 * application data — sending any would make the server close the socket with code 1008 — so this
 * class only performs the handshake and reads frames.
 *
 * [authorization] rides on the upgrade itself. That is the whole credential for this socket: there
 * is no later request to carry one, and `dsh-relay` refuses an unauthenticated upgrade at the
 * handshake. Both downlinks must open inside the loop's 3000ms budget, so a missing header here
 * costs the entire connection generation rather than one call.
 */
open class WsDownlink(
    private val url: String,
    private val client: OkHttpClient,
    private val sink: WsDownlinkSink,
    private val authorization: String? = null,
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
            val frame = try {
                decodeServerRequest(text)
            } catch (e: SerializationException) {
                // Protocol drift on the downlink: terminate this generation so the loop reconnects.
                webSocket.cancel()
                sink.onClosed(e)
                return
            } catch (e: IllegalArgumentException) {
                webSocket.cancel()
                sink.onClosed(e)
                return
            }
            sink.onFrame(frame)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sink.onClosed(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // A rejected upgrade carries its status only on the response; OkHttp reports it as a
            // bare ProtocolException ("Expected HTTP 101 … was '403'"), which no caller can
            // classify without reading English. Re-wrap so a trust-fence rejection of the stream
            // reads the same as one on a POST.
            sink.onClosed(
                if (response != null) {
                    RpcTransportException(response.code, carrierMessage(response.code), t)
                } else {
                    t
                },
            )
        }
    }

    /** Perform the RFC 6455 handshake and begin reading frames. Idempotent. */
    open fun start() {
        if (started) return
        started = true
        val request = Request.Builder().url(url).authorized(authorization).build()
        webSocket = client.newWebSocket(request, listener)
    }

    /** Tear the socket down. Idempotent. */
    open fun close() {
        webSocket?.cancel()
        webSocket = null
    }
}
