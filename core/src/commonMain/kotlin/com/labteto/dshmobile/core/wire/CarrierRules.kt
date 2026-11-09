package com.labteto.dshmobile.core.wire

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The rules every HTTP carrier has to apply the same way, whichever library it sits on.
 *
 * `OkHttpRpcTransport` (JVM) and `KtorRpcTransport` (common) each own their I/O, but not the facts
 * the harness and `dsh-relay` care about: how the `Host` header is spelled, what a refusal says,
 * how a `Retry-After` is read. Those live here so a second transport cannot drift from the first —
 * a fence rejection of `/api/remote.mux` has to read the same as one on `/api/host.describe`
 * whichever carrier saw it.
 */

/**
 * The `Host` header for requests to [host]:[port] over [scheme].
 *
 * The port is omitted when it is the scheme's default, which is what a relay behind a name on :443
 * needs. An IPv6 literal is bracketed: both dsh-relay's fence and the harness's
 * `isTrustedApiRequest` parse `Host` with `new URL("http://" + host)`, which throws on the
 * unbracketed form, so on an IPv6-only network every `/api` call came back refused while pairing,
 * health and the WebSocket upgrade all worked, because nothing but the unary transport sets the
 * header by hand. [host] is the *bare* host — callers whose URL parser keeps the brackets must
 * strip them first.
 */
internal fun hostHeaderFor(scheme: String, host: String, port: Int): String {
    val defaultPort = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
    val literal = if (':' in host) "[$host]" else host
    return if (port == defaultPort) literal else "$literal:$port"
}

/**
 * How long the peer asked the caller to wait, from a `Retry-After` value on a 429.
 *
 * A malformed or absent header reads as "unstated" rather than "retry now": older relays omit it
 * on their lockout paths, and a client that treated silence as zero would hammer an address that
 * had just told it to stop. Only the delta-seconds form is understood; an HTTP-date is "unstated".
 */
internal fun retryAfterSecondsOf(header: String?): Long? =
    header?.trim()?.toLongOrNull()?.coerceAtLeast(1)

/**
 * Whether a response of this `Content-Type` is harness 0.1.7's multipart answer (see
 * `RpcMultipart`) rather than JSON. Shared because the carrier has to decide *before* it reads the
 * body whether to hand back text or raw bytes.
 */
internal fun isMultipartContentType(contentType: String?): Boolean =
    contentType?.trimStart()?.startsWith("multipart/", ignoreCase = true) == true

/**
 * Carrier-layer failure text; 403 names the trust fence because that is the usual cause.
 *
 * Shared so the WebSocket path can wrap a failed upgrade in the same shape as a failed POST — a
 * fence rejection of `/api/remote.mux` is the same fact as one on `/api/host.describe`.
 */
internal fun carrierMessage(status: Int, body: String? = null): String = when (status) {
    // Since 0.1.2 these are two different facts and the connect screen has to say which. 403 is
    // the Host/Origin fence, which runs first and is about *where* the request came from. 401 is
    // the browser session, which is about *who* is asking — a harness that would answer happily
    // if this client had exchanged a launch token. Collapsing them sends people to reconfigure a
    // firewall when they actually need to re-pair.
    401 -> "harness has no browser session for this client (HTTP 401)"
    // The harness caps a request body (300 MiB by default) and answers 413 rather than reading it.
    // Saying so beats "not a harness", which is what this used to read as: the address was right
    // and the attachment was too big.
    413 -> "the harness refused the request as too large (HTTP 413)"
    // Only a relay throttles; the harness itself does not.
    429 -> "rate limited before the harness (HTTP 429)"
    // A relay or reverse proxy answered and the harness behind it did not.
    502 -> "nothing answered behind the relay (HTTP 502)"
    // A relay in front of the harness runs its own fence, and when it refuses, the harness never
    // sees the request at all — so naming the harness sends people to debug the wrong machine.
    // The relay says why in the body; when it does, that reason is the message. Otherwise the
    // wording stands, because an empty 403 really is most likely the harness's own fence.
    403 -> refusalReason(body)?.let { "request refused before the harness: $it (HTTP 403)" }
        ?: "harness trust fence rejected the request (HTTP 403)"
    else -> "carrier returned HTTP $status"
}

/**
 * The `error` a refusal body names, when it is shaped like one.
 *
 * Deliberately forgiving: this runs on a failure path, against a body written by whatever is in
 * front of the harness, and a parse failure here must not replace a usable status with a crash.
 */
private fun refusalReason(body: String?): String? {
    val text = body?.trim().orEmpty()
    if (!text.startsWith("{")) return null
    val reason = runCatching {
        WireJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return reason?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
}
