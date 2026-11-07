package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/** `application/json; charset=utf-8`, spelled as OkHttp writes it on the wire. */
private val JSON_CONTENT_TYPE: ContentType = ContentType.parse("application/json; charset=utf-8")

/** Package operations are host-owned and may outlive the ordinary RPC read budget. */
private val UNBOUNDED_READ_PATHS = setOf("/api/pluginManager/installBundle", "/api/pluginManager/waitForInstall")

/**
 * A multiplatform [RpcTransport] on Ktor: the iOS carrier, and a second, independent implementation
 * of the seam `OkHttpRpcTransport` sits behind.
 *
 * The caller injects the [HttpClient], so the same class runs on a Darwin engine on iOS (see
 * `darwinHttpClient`) and on a JVM engine in the test and conformance suites, which is how an iOS
 * transport is proven before any iOS device exists. Build the client with [dshCarrier] applied (the
 * Darwin factory does) so redirects and timeouts behave as this class expects.
 *
 * Everything the harness or `dsh-relay` can observe matches `OkHttpRpcTransport`, and where it
 * could not, the difference is listed on the iOS plan (`docs/IOS.md`) rather than left to be found:
 * - `Host` is written by hand with [hostHeaderFor]'s rule, `Content-Type` is
 *   `application/json; charset=utf-8` for a unary POST, and `Authorization` / `Cookie` are set
 *   only when given.
 * - [connectTimeoutMs] / [readTimeoutMs] default to 30s. Package operations
 *   (`pluginManager/installBundle`, `waitForInstall`) get no read deadline; downloads and uploads
 *   get a ten-minute one, since a session-log ZIP is compressed on the fly and an upload is bounded
 *   by the link. A value of 0 means no deadline, as it does for OkHttp.
 * - A non-2xx answer throws [RpcTransportException] with [carrierMessage]'s text, the status, and
 *   a 429's `Retry-After`; a network failure throws it with status 0 and the cause attached, which
 *   is what [TransportFailures.classify] reads.
 * - A multipart answer (`Content-Type: multipart/form-data`) arrives in [RpcHttpResponse.multipart]
 *   as raw bytes.
 * - A download hands [RpcTransport.download]'s consumer a live [Source] over the response body; an
 *   upload pulls from its [Source] as the request is written. Neither is buffered whole here.
 *
 * Redirects are not followed (see [dshCarrier]): an `/api` call that is redirected surfaces as a
 * non-2xx [RpcTransportException] rather than replaying a credential at another address.
 */
class KtorRpcTransport(
    baseUrl: String,
    private val client: HttpClient,
    connectTimeoutMs: Long = 30_000,
    readTimeoutMs: Long = 30_000,
    private val authorization: String? = null,
    private val cookie: String? = null,
) : RpcTransport {

    private val endpoint = HarnessEndpoint.of(baseUrl)

    /** Whether to compare a body's size with its `Content-Length`; see [DshBodyLengthCheck]. */
    private val checkBodyLength: Boolean = client.attributes.getOrNull(DshBodyLengthCheck) ?: true
    private val connectTimeout = connectTimeoutMs.asKtorTimeout()
    private val readTimeout = readTimeoutMs.asKtorTimeout()

    override suspend fun post(path: String, body: String): RpcHttpResponse {
        // Resolved before the request so a bad route is the IllegalArgumentException it is for
        // OkHttp, not a transport failure.
        val target = endpoint.resolve(path)
        val socketTimeout = if (path in UNBOUNDED_READ_PATHS) INFINITE else readTimeout
        return carrier {
            val response = client.post {
                carrierRequest(target, connectTimeout, socketTimeout)
                setBody(TextContent(body, JSON_CONTENT_TYPE))
            }
            readUnary(response)
        }
    }

    private suspend fun readUnary(response: HttpResponse): RpcHttpResponse {
        val status = response.status.value
        val contentType = response.headers[HttpHeaders.ContentType]
        val success = status in 200..299
        val bytes = response.bodyAsBytes()
        // Some engines end a body quietly when the connection drops short of its Content-Length.
        // OkHttp throws there, and a half-read envelope must not parse as an answer.
        response.expectedBodyLength(checkBodyLength)?.let { expected ->
            if (bytes.size.toLong() != expected) {
                throw IOException("unexpected end of stream: read ${bytes.size} of $expected bytes")
            }
        }
        if (success && isMultipartContentType(contentType)) {
            return RpcHttpResponse(status, "", contentType, bytes)
        }
        // The body is saved, so reading it again as text is a decode, not a second read.
        val text = response.bodyAsText()
        if (success) return RpcHttpResponse(status, text, contentType)
        throw RpcTransportException(
            status,
            carrierMessage(status, text),
            retryAfterSeconds = retryAfterSecondsOf(response.headers[HttpHeaders.RetryAfter]),
        )
    }

    override suspend fun <T> download(
        path: String,
        consume: (contentType: String?, contentDisposition: String?, body: Source) -> T,
    ): T {
        val target = endpoint.resolve(path)
        // What `consume` itself throws is the caller's business and must come out as it went in;
        // only the carrier's own failures become transport exceptions.
        var consumerFailure: Throwable? = null
        return withContext(blockingDispatcher) {
            carrier(passThrough = { it === consumerFailure }) {
                client.prepareGet { carrierRequest(target, connectTimeout, DOWNLOAD_IDLE_MS) }.execute { response ->
                    val status = response.status.value
                    if (status !in 200..299) throw RpcTransportException(status, carrierMessage(status))
                    // A live view of the response channel, not a copy: reads pull from the
                    // connection as `consume` makes them, and leaving this block releases it.
                    val source = ChannelSource(response.bodyAsChannel(), response.expectedBodyLength(checkBodyLength)).buffered()
                    try {
                        consume(
                            response.headers[HttpHeaders.ContentType],
                            response.headers[HttpHeaders.ContentDisposition],
                            source,
                        )
                    } catch (e: Throwable) {
                        consumerFailure = e
                        throw e
                    } finally {
                        source.close()
                    }
                }
            }
        }
    }

    override suspend fun upload(
        path: String,
        contentType: String,
        contentLength: Long,
        body: Source,
        onProgress: ((sent: Long) -> Unit)?,
    ): RpcHttpResponse {
        val target = endpoint.resolve(path)
        val content = SourceContent(ContentType.parse(contentType), contentLength, body, onProgress)
        return carrier {
            val response = client.post {
                carrierRequest(target, connectTimeout, DOWNLOAD_IDLE_MS)
                setBody(content)
            }
            val status = response.status.value
            val text = response.bodyAsText()
            if (status !in 200..299) throw RpcTransportException(status, carrierMessage(status))
            RpcHttpResponse(status, text)
        }
    }

    private fun HttpRequestBuilder.carrierRequest(target: String, connectMs: Long, socketMs: Long) {
        url(target)
        header(HttpHeaders.Host, endpoint.hostHeader)
        if (authorization != null) header(HttpHeaders.Authorization, authorization)
        if (cookie != null) header(HttpHeaders.Cookie, cookie)
        timeout {
            connectTimeoutMillis = connectMs
            socketTimeoutMillis = socketMs
        }
    }

    private companion object {
        const val INFINITE = HttpTimeoutConfig.INFINITE_TIMEOUT_MS

        /**
         * Downloads and uploads relax the 30s deadline: a session log ZIP is streamed and
         * compressed on the fly, and a file upload is bounded by the link rather than the host.
         */
        const val DOWNLOAD_IDLE_MS = 10 * 60 * 1000L

        /** One upload read: a kotlinx-io segment, the most a buffered [Source] returns at once. */
        const val UPLOAD_READ_BYTES = 8 * 1024

        /** How many bytes an upload advances between two progress reports. */
        const val UPLOAD_PROGRESS_STEP_BYTES = 64 * 1024

        fun Long.asKtorTimeout(): Long = if (this <= 0) INFINITE else this
    }

    /**
     * The request body of an upload: [source] streamed to the connection as it is written.
     *
     * Reads are blocking (a kotlinx-io `Source` is), so each runs on the [blockingDispatcher] rather than
     * on the engine's own thread. Progress is reported on the cadence `OkHttpRpcTransport` keeps —
     * every 64 KiB plus a final total — because kotlinx-io hands back at most one 8 KiB segment per
     * read and reporting each would multiply the app's progress callbacks (each one a Compose state
     * update) by eight.
     */
    private class SourceContent(
        override val contentType: ContentType,
        private val length: Long,
        private val source: Source,
        private val onProgress: ((Long) -> Unit)?,
    ) : OutgoingContent.WriteChannelContent() {
        override val contentLength: Long get() = length

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val buffer = ByteArray(UPLOAD_READ_BYTES)
            var sent = 0L
            var reported = 0L
            while (true) {
                val read = withContext(blockingDispatcher) { source.readAtMostTo(buffer) }
                if (read < 0) break
                channel.writeFully(buffer, 0, read)
                sent += read
                if (onProgress != null && sent - reported >= UPLOAD_PROGRESS_STEP_BYTES) {
                    onProgress(sent)
                    reported = sent
                }
            }
            if (onProgress != null && sent != reported) onProgress(sent)
        }
    }
}

/**
 * Settings a Ktor client needs to carry DSH traffic, applied inside the `HttpClient { … }` block.
 *
 * - **No redirects.** Neither the harness nor the relay redirects an `/api` call, and following one
 *   would replay the `Authorization` / `Cookie` this transport sets at whatever address answered.
 *   The Darwin engine already refuses redirects at the session level; this makes the JVM engines
 *   agree.
 * - **`HttpTimeout`**, so the per-request connect and read deadlines [KtorRpcTransport] sets are
 *   honoured by every engine rather than only the ones that read them without the plugin.
 * - **`WebSockets`**, which [KtorMuxSocket] needs for `/api/remote.mux`, with a 128 MiB message
 *   limit. No client-initiated ping: the harness pings and the engine answers (see [KtorMuxSocket]).
 */
fun HttpClientConfig<*>.dshCarrier() {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout)
    install(WebSockets) {
        // A session snapshot is one text frame. The plugin only forwards this to the engine when it
        // differs from its default, and the Darwin engine otherwise keeps NSURLSessionWebSocketTask's
        // own limit of 1 MiB, past which the task fails with "message too long". OkHttp enforces no
        // limit on a message, so this is large enough to never be the thing that decides.
        maxFrameSize = MAX_MUX_FRAME_BYTES
    }
}

/** The largest mux message the Ktor carrier accepts: 128 MiB. */
private const val MAX_MUX_FRAME_BYTES = 128L * 1024 * 1024

/**
 * Client attribute: `false` on a client whose engine already fails a body that ends short, so
 * [KtorRpcTransport] does not second-guess it.
 *
 * By default a body whose size differs from its `Content-Length` fails the call: Ktor's CIO engine
 * (the JVM one this is tested on) ends such a body quietly, and a session-log ZIP cut off by a
 * dropped connection would otherwise be saved as if it were whole. `NSURLSession` reports a short
 * body as an error itself, and may also decode a compressed response while leaving the compressed
 * `Content-Length` behind, which this check would misread as truncation; the Darwin factory turns
 * the check off for that reason.
 */
val DshBodyLengthCheck: AttributeKey<Boolean> = AttributeKey("dsh.bodyLengthCheck")

/**
 * The body length [this] declared, when it can be checked against the bytes received.
 *
 * Null when the response says nothing, or when the body was transformed on the way in (a
 * `Content-Encoding` the engine or the OS undid, so the declared length counts compressed bytes).
 */
private fun HttpResponse.expectedBodyLength(enabled: Boolean): Long? {
    if (!enabled) return null
    val encoding = headers[HttpHeaders.ContentEncoding]
    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return null
    return headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
}

/**
 * Run a carrier call and turn an engine failure into the [RpcTransportException] the seam promises.
 *
 * An [RpcTransportException] already thrown inside is left alone. A cancellation of the *calling*
 * coroutine is rethrown as cancellation; a cancellation the engine raised on its own (a timeout
 * cancels the call's job) is a transport failure with that cause. [passThrough] names throwables
 * that came from the caller's own code and must not be wrapped.
 */
internal suspend inline fun <T> carrier(
    crossinline passThrough: (Throwable) -> Boolean = { false },
    block: () -> T,
): T = try {
    block()
} catch (e: RpcTransportException) {
    throw e
} catch (e: Throwable) {
    if (passThrough(e)) throw e
    if (e is CancellationException) {
        currentCoroutineContext().ensureActive()
        val cause = e.cause ?: e
        throw RpcTransportException(0, "transport failure: ${cause.message}", cause)
    }
    if (e is Error) throw e
    // OkHttp lets a bad route through as the IllegalArgumentException it is; a body or engine
    // failure is a transport failure whatever its type.
    throw RpcTransportException(0, "transport failure: ${e.message}", e)
}

/**
 * A blocking kotlinx-io view of a Ktor [ByteReadChannel].
 *
 * `RpcTransport.download` gives its consumer a plain synchronous [Source], so each read bridges to
 * the channel's suspending read with [runBlocking]; it only ever runs on an IO thread (see
 * `download`), where blocking is the contract. Failures of the channel surface as [IOException]s,
 * since that is what a failed read of a `Source` is expected to throw, and closing the source
 * cancels the channel so an unread remainder is discarded rather than drained.
 */
private class ChannelSource(
    private val channel: ByteReadChannel,
    /** The body length the response declared, when the bytes on the wire are the bytes declared. */
    private val expectedLength: Long?,
) : RawSource {
    private val scratch = ByteArray(8 * 1024)
    private var closed = false
    private var total = 0L

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(!closed) { "source is closed" }
        require(byteCount >= 0) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0
        val read = try {
            runBlocking { channel.readAvailable(scratch, 0, minOf(byteCount, scratch.size.toLong()).toInt()) }
        } catch (e: IOException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message, e)
        }
        if (read < 0) {
            // A body that ends short of its Content-Length is a failed download, not a short file:
            // a session-log ZIP cut off at the connection must not be saved as if it were whole.
            if (expectedLength != null && total != expectedLength) {
                throw IOException("unexpected end of stream: read $total of $expectedLength bytes")
            }
            return -1
        }
        total += read
        sink.write(scratch, 0, read)
        return read.toLong()
    }

    override fun close() {
        if (closed) return
        closed = true
        channel.cancel(null)
    }
}
