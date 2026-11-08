package com.labteto.dshmobile.core.wire

/**
 * One plain HTTP request, for the exchanges that happen before there is a harness connection to
 * speak to: the launch-token exchange (`GET /?token=…`) and the relay's `/relay/health` and
 * `/relay/pair`. They are not harness RPC (no envelope, no `/api`), so they do not go through
 * [RpcTransport]; they need a smaller thing, and this is it.
 */
class PlainHttpRequest(
    /** `GET` or `POST`. */
    val method: String,
    /** The absolute URL, already canonical. */
    val url: String,
    /** Extra request headers, in order. `Content-Type` is not one: a body brings its own. */
    val headers: List<Pair<String, String>> = emptyList(),
    /** The request body, sent as UTF-8; null for none. */
    val body: String? = null,
    /** The body's `Content-Type`, e.g. `application/json; charset=utf-8`. */
    val bodyContentType: String? = null,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    /** Whether the caller wants the response body. When false [PlainHttpResponse.body] is empty and the body is not read. */
    val readBody: Boolean = true,
)

/** The answer to a [PlainHttpRequest]: a status, a body and the headers, whatever the status. */
class PlainHttpResponse(
    val status: Int,
    val body: String,
    headers: Map<String, List<String>> = emptyMap(),
) {
    private val byName: Map<String, List<String>> =
        headers.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, lists) -> lists.flatten() }

    /** Every value the response sent for [name] (case-insensitive), in order; empty when it sent none. */
    fun headers(name: String): List<String> = byName[name.lowercase()].orEmpty()
}

/**
 * The HTTP carrier of the pre-connection exchanges.
 *
 * - **No redirects are followed.** A 3xx is an answer, returned as one: the launch-token exchange
 *   reads its cookie off a 303, and the relay probe reads a redirect's `Location` to find the real
 *   listener. Following either would discard the thing the call exists to read.
 * - **Any status is an answer.** A 4xx or 5xx comes back as a [PlainHttpResponse]; it is the
 *   caller's business to read it.
 * - **Failing to get an answer throws a [kotlinx.io.IOException]** (connection refused, timeout,
 *   TLS, DNS, a body that dies half-read), with the platform's own exception as the cause or as
 *   itself, so [TransportFailures.classify] can say why. Cancelling the calling coroutine cancels
 *   the request.
 *
 * `OkHttpPlainHttp` is the Android implementation and `KtorPlainHttp` the multiplatform one.
 */
interface PlainHttp {
    suspend fun execute(request: PlainHttpRequest): PlainHttpResponse
}
