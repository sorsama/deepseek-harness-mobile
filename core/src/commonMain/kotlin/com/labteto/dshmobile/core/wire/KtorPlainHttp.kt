package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.util.toMap

/**
 * [PlainHttp] on Ktor: the multiplatform implementation, on the Darwin engine on iOS (see
 * `darwinHttpClient`) and on a JVM engine in tests.
 *
 * Like [KtorRpcTransport], the caller injects the [HttpClient] and builds it with [dshCarrier]
 * applied, which switches redirects off (the contract of [PlainHttp]) and installs the timeout
 * plugin this class sets per request. A relay pinned by key needs its own client
 * (`darwinHttpClient(pinnedSpkiSha256 = …)`) and so does one trusted on first use
 * (`darwinHttpClient(observedKey = …)`), as a pinned or trust-on-first-use OkHttp client is its own.
 *
 * `Host` is written by hand with [hostHeaderFor]'s rule, as [KtorRpcTransport] does, so a relay or
 * harness behind a fence reads the same value whichever carrier asked. Every failure to get an
 * answer is an [RpcTransportException] with status 0 and the engine's exception as its cause, which
 * is what [TransportFailures.classify] reads.
 *
 * **Combined headers.** Darwin's `NSHTTPURLResponse` folds a repeated header into one value joined by
 * `", "`, so [PlainHttpResponse.headers] may hold one `Set-Cookie` that is really several; the
 * launch-token exchange knows to look inside it.
 */
class KtorPlainHttp(private val client: HttpClient) : PlainHttp {

    override suspend fun execute(request: PlainHttpRequest): PlainHttpResponse {
        // Resolved before the request so a bad URL is the IllegalArgumentException it is for OkHttp,
        // not a transport failure.
        val target = parseWireUrl(request.url)
            ?: throw IllegalArgumentException("Expected an http or https URL: ${request.url}")
        val verb = when (request.method) {
            "GET" -> HttpMethod.Get
            "POST" -> HttpMethod.Post
            else -> throw IllegalArgumentException("unsupported method ${request.method}")
        }
        val connectMs = request.connectTimeoutMs.asKtorTimeout()
        val readMs = request.readTimeoutMs.asKtorTimeout()
        return carrier {
            client.prepareRequest {
                method = verb
                url(request.url)
                header(HttpHeaders.Host, hostHeaderFor(target.scheme, target.host, target.port))
                request.headers.forEach { (name, value) -> header(name, value) }
                timeout {
                    connectTimeoutMillis = connectMs
                    socketTimeoutMillis = readMs
                }
                request.body?.let { body ->
                    setBody(TextContent(body, request.bodyContentType?.let(ContentType::parse) ?: ContentType.Text.Plain))
                }
            }.execute { response ->
                // Leaving this block releases the connection, so a body nobody asked for is never read.
                PlainHttpResponse(
                    status = response.status.value,
                    body = if (request.readBody) response.bodyAsText() else "",
                    headers = response.headers.toMap(),
                )
            }
        }
    }

    private fun Long.asKtorTimeout(): Long = if (this <= 0) HttpTimeoutConfig.INFINITE_TIMEOUT_MS else this
}
