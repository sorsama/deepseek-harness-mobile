package com.labteto.dshmobile.core.wire

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
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
import okio.BufferedSink

/** JSON media type used for every /api POST. */
private val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

/**
 * OkHttp-backed [RpcTransport]. Sends `Content-Type: application/json`, sets the `Host` header
 * from the base URL, and times out at [connectTimeoutMs]/[readTimeoutMs] (30s by default). Non-2xx
 * responses throw [RpcTransportException] (403 mentions the harness trust fence).
 *
 * [baseUrl] may be `http://` or `https://`; see [hostHeaderFor] for how the header is spelled.
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
    private val hostHeader: String = hostHeaderFor(base)
    private val httpClient: OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val packageClient by lazy { httpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build() }

    override suspend fun post(path: String, body: String): RpcHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val target = resolveHarnessUrl(base.toString(), path)
            val request = Request.Builder()
                .url(target)
                .header("Host", hostHeader)
                .header("Content-Type", "application/json")
                .authorized(authorization)
                .cookied(cookie)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            // Package operations are host-owned and may outlive the ordinary RPC read budget.
            // Keep the connection budget, and let explicit cancellation or the host settle them.
            val carrier = if (path in setOf("/api/pluginManager/installBundle", "/api/pluginManager/waitForInstall")) packageClient else httpClient
            val call = carrier.newCall(request)
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
                        val contentType = resp.header("Content-Type")
                        if (resp.isSuccessful && RpcMultipart.isMultipart(contentType)) {
                            val bytes = try {
                                resp.body?.bytes() ?: ByteArray(0)
                            } catch (e: IOException) {
                                continuation.resumeWithException(
                                    RpcTransportException(0, "transport failure: ${e.message}", e),
                                )
                                return
                            }
                            continuation.resume(RpcHttpResponse(resp.code, "", contentType, bytes))
                            return
                        }
                        val responseBody = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            continuation.resume(RpcHttpResponse(resp.code, responseBody, contentType))
                        } else {
                            continuation.resumeWithException(
                                RpcTransportException(
                                    resp.code,
                                    carrierMessage(resp.code, responseBody),
                                    retryAfterSeconds = retryAfterSecondsOf(resp),
                                ),
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
        consume: (contentType: String?, contentDisposition: String?, body: Source) -> T,
    ): T = withContext(Dispatchers.IO) {
        val target = resolveHarnessUrl(base.toString(), path)
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
            // A live view of the response stream, not a copy: `asSource()` pulls from the
            // connection as `consume` reads, and `response.use` still closes it afterwards.
            consume(
                resp.header("Content-Type"),
                resp.header("Content-Disposition"),
                body.byteStream().asSource().buffered(),
            )
        }
    }

    override suspend fun upload(
        path: String,
        contentType: String,
        contentLength: Long,
        body: Source,
        onProgress: ((sent: Long) -> Unit)?,
    ): RpcHttpResponse = withContext(Dispatchers.IO) {
        val target = resolveHarnessUrl(base.toString(), path)
        val requestBody = object : RequestBody() {
            override fun contentType(): MediaType? = contentType.toMediaType()
            override fun contentLength(): Long = contentLength
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(UPLOAD_READ_BYTES)
                var sent = 0L
                var reported = 0L
                while (true) {
                    val read = body.readAtMostTo(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    sent += read
                    // kotlinx-io hands back at most one 8 KiB segment per read, where the
                    // `InputStream` this replaced returned up to 64 KiB. Reporting on every read
                    // would have multiplied the progress callbacks (each one is a Compose state
                    // update in the app) by eight, so report on the old cadence instead: every
                    // 64 KiB, plus the final total below. The bytes are written as soon as they are
                    // read either way; only the reporting is paced.
                    if (onProgress != null && sent - reported >= UPLOAD_PROGRESS_STEP_BYTES) {
                        onProgress(sent)
                        reported = sent
                    }
                }
                if (onProgress != null && sent != reported) onProgress(sent)
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
        /** One upload read: a kotlinx-io segment, the most a buffered [Source] returns at once. */
        const val UPLOAD_READ_BYTES = 8 * 1024

        /** How many bytes an upload advances between two progress reports. */
        const val UPLOAD_PROGRESS_STEP_BYTES = 64 * 1024

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
 * The `Host` header for requests against [base].
 *
 * [HttpUrl.host] hands back the canonical *unbracketed* host (`http://[::1]:3080` → `::1`), which
 * is exactly the bare form the shared [hostHeaderFor] takes; that function owns the spelling rule
 * (default port omitted, IPv6 literal bracketed), so every carrier applies it identically.
 */
internal fun hostHeaderFor(base: HttpUrl): String = hostHeaderFor(base.scheme, base.host, base.port)

/** `Retry-After` on a 429; the shared [retryAfterSecondsOf] owns how the value is read. */
internal fun retryAfterSecondsOf(response: Response): Long? =
    retryAfterSecondsOf(response.header("Retry-After"))
