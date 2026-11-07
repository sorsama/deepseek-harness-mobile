package com.labteto.dshmobile.core.wire

import kotlinx.io.IOException
import kotlinx.io.Source

/**
 * One HTTP carrier exchange: the status code and raw body of a POST /api request.
 * HTTP status is carrier-only — business errors arrive as HTTP 200 with `ok: false` in the body.
 */
data class RpcHttpResponse(
    val status: Int,
    val body: String,
    /** The response `Content-Type`, when the carrier reported one. */
    val contentType: String? = null,
    /**
     * The undecoded body of a multipart answer, harness 0.1.7's shape for a result that carries
     * raw bytes (see `RpcMultipart`); [body] is empty then. Null for every JSON answer.
     */
    val multipart: ByteArray? = null,
)

/**
 * Thrown by [RpcTransport] on a non-2xx carrier response or a network-level failure.
 *
 * Extends [kotlinx.io.IOException] rather than `java.io.IOException` so this type can live in
 * common code. On the JVM that is not a different class: kotlinx-io declares its `IOException` as
 * a typealias of `java.io.IOException` there, so this is still a `java.io.IOException` and every
 * existing `catch (e: IOException)` in the Android app keeps catching it. `TransportSeamTest`
 * pins that identity, because the whole app's error handling leans on it.
 */
class RpcTransportException(
    val status: Int,
    message: String,
    cause: Throwable? = null,
    /**
     * Seconds the peer asked the caller to wait, from `Retry-After` on a 429.
     *
     * Only a relay throttles today, and older ones omitted the header on their lockout paths, so
     * an absent value is normal rather than a fault — the caller falls back to its own default
     * rather than retrying immediately.
     */
    val retryAfterSeconds: Long? = null,
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
     * [consume] runs on an IO thread with the response still open and must not retain the
     * [Source] — the connection is released as soon as it returns, and a later read fails. The
     * source is a live view of the response body: bytes are pulled from the connection as
     * [consume] reads them, so a large download is never held in memory by the transport. Throws
     * [RpcTransportException] on a non-2xx status or transport failure.
     */
    suspend fun <T> download(
        path: String,
        consume: (contentType: String?, contentDisposition: String?, body: Source) -> T,
    ): T

    /**
     * POST raw bytes to [path] — the harness's one non-envelope *write* channel, the streaming
     * file-upload route harness 0.1.3 added (`/api/session/uploadFileBinary`).
     *
     * [body] is read once on an IO thread and must be positioned at the first byte to send; it is
     * pulled from as the request is written, never read into memory whole. [contentLength] is
     * declared up front so the host can refuse an oversized upload before reading it.
     * [onProgress] is called with the running byte count as the body is written: non-decreasing,
     * in steps of roughly 64 KiB, and once more with the final total when the body ends. Returns
     * the carrier response (the route answers 200 with a bare `{ok, value|error}` result), or
     * throws [RpcTransportException] on a non-2xx status or transport failure.
     */
    suspend fun upload(
        path: String,
        contentType: String,
        contentLength: Long,
        body: Source,
        onProgress: ((sent: Long) -> Unit)? = null,
    ): RpcHttpResponse
}
