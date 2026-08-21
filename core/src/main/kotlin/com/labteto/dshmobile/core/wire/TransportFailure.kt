package com.labteto.dshmobile.core.wire

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why a carrier-level call did not produce an answer.
 *
 * The distinction that matters most is [REFUSED] against [TIMEOUT]: a refused connection means the
 * computer is reachable and nothing is listening on that port — the harness is still bound to
 * loopback, or the port is wrong — while a timeout means the packets were dropped, which is a
 * firewall or a router keeping wireless clients away from wired ones. Those two need opposite
 * instructions, and collapsing them into "could not connect" is what made the failure unactionable.
 */
enum class TransportFailure {
    /** ECONNREFUSED — something answered the network but nothing listens on the port. */
    REFUSED,

    /** The connect or read deadline passed with no answer at all — dropped, not refused. */
    TIMEOUT,

    /** The name did not resolve. */
    DNS,

    /** No route / port unreachable — usually a different network. */
    UNREACHABLE,

    /** HTTP 403: the harness answered and its `Host` trust fence rejected the request. */
    TRUST_FENCE,

    /** HTTP 404: no route claimed the path; the build does not compose that service. */
    NOT_FOUND,

    /** Something answered, but it does not speak the harness protocol. */
    NOT_A_HARNESS,

    /**
     * The TCP connection opened but the TLS handshake did not survive it: a certificate this
     * device does not trust, or `https://` aimed at a server speaking plain HTTP.
     */
    TLS,

    /** Anything else. */
    OTHER,
}

/**
 * Classification helpers for [TransportFailure].
 *
 * Classification happens here, next to the typed exception, rather than in the app: by the time a
 * failure has crossed into `RpcResult.Err` the only thing left is an English message string, and
 * matching on that would make a log line into an API. The verdict rides along in
 * [RpcError.details] instead, which is already a free-form slot on the envelope.
 *
 * Pure JVM (`java.net`), so `:core` stays free of Android imports.
 */
object TransportFailures {

    /** Key under which the [TransportFailure] name is written into [RpcError.details]. */
    const val DETAILS_KEY: String = "transport"

    /** Key under which the originating HTTP status is written, when there was one. */
    const val STATUS_KEY: String = "httpStatus"

    /** Classify a carrier exception: HTTP status first, then the underlying I/O cause. */
    fun classify(e: RpcTransportException): TransportFailure = when (e.status) {
        403 -> TransportFailure.TRUST_FENCE
        404 -> TransportFailure.NOT_FOUND
        0 -> classify(e.cause)
        // A 5xx or a stray 200-shaped answer from something that is not the harness.
        else -> TransportFailure.NOT_A_HARNESS
    }

    /** Classify a raw throwable — used for WebSocket failures and the socket pre-flight. */
    fun classify(t: Throwable?): TransportFailure = when (t) {
        null -> TransportFailure.OTHER
        is RpcTransportException -> classify(t)
        is SocketTimeoutException -> TransportFailure.TIMEOUT
        is UnknownHostException -> TransportFailure.DNS
        is NoRouteToHostException, is PortUnreachableException -> TransportFailure.UNREACHABLE
        is ConnectException -> TransportFailure.REFUSED
        // Before the IOException arm: SSLException is an IOException, and its message would
        // otherwise be sniffed for words it does not contain.
        is SSLException -> TransportFailure.TLS
        is IOException -> classifyByMessage(t)
        else -> TransportFailure.OTHER
    }

    /**
     * Last resort for an [IOException] with no distinguishing subtype.
     *
     * Android's socket layer can surface a kernel connect deadline as a plain `IOException` naming
     * ETIMEDOUT rather than as [SocketTimeoutException]. This is a fallback only — the manual
     * connect path enforces its own deadline with a raw socket precisely so it does not depend on
     * this guess.
     */
    private fun classifyByMessage(t: IOException): TransportFailure {
        val message = t.message?.lowercase() ?: return TransportFailure.OTHER
        return when {
            "etimedout" in message || "timed out" in message || "timeout" in message -> TransportFailure.TIMEOUT
            "econnrefused" in message || "refused" in message -> TransportFailure.REFUSED
            "enetunreach" in message || "ehostunreach" in message || "unreachable" in message ->
                TransportFailure.UNREACHABLE
            else -> TransportFailure.OTHER
        }
    }

    /** The `details` object carrying [kind] (and [status], when non-zero) for [RpcError]. */
    fun details(kind: TransportFailure, status: Int = 0): JsonObject = buildJsonObject {
        put(DETAILS_KEY, kind.name)
        if (status != 0) put(STATUS_KEY, status)
    }

    /** Read the marker back out of an error, or null when the error carries none. */
    fun of(error: RpcError): TransportFailure? {
        val name = (error.details as? JsonObject)?.get(DETAILS_KEY)?.jsonPrimitive?.content ?: return null
        return TransportFailure.entries.firstOrNull { it.name == name }
    }

    /** The HTTP status recorded alongside the marker, when there was one. */
    fun statusOf(error: RpcError): Int? = runCatching {
        error.details.jsonObject[STATUS_KEY]?.jsonPrimitive?.int
    }.getOrNull()
}
