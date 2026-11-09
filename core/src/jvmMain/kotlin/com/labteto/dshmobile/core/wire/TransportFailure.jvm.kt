package com.labteto.dshmobile.core.wire

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException

/** How far [hasPinMismatch] follows a cause chain before giving up. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * JVM actual: whether [t] or anything it wraps is a [PinMismatchException].
 *
 * The pin check walks the cause chain because OkHttp wraps whatever the trust manager threw in an
 * `SSLHandshakeException`, so the marker is never the throwable handed in here.
 */
internal actual fun hasPinMismatch(t: Throwable): Boolean {
    // Bounded rather than "walk until null": a self-referential cause chain is rare but it is a
    // hang, and nothing legitimate nests this deep.
    var cause: Throwable? = t
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is PinMismatchException) return true
        cause = cause.cause
        depth++
    }
    return false
}

/**
 * JVM actual: classify by the `java.net` / `javax.net.ssl` exception types the OkHttp and raw
 * socket paths throw. This is the logic that lived in `TransportFailures` before it moved to
 * common code, unchanged.
 */
internal actual fun classifyPlatformFailure(t: Throwable): TransportFailure = when (t) {
    is SocketTimeoutException -> TransportFailure.TIMEOUT
    is UnknownHostException -> TransportFailure.DNS
    // Ktor's CIO engine resolves with NIO, which reports an unresolvable name this way rather than
    // as an `UnknownHostException`. OkHttp never throws it.
    is UnresolvedAddressException -> TransportFailure.DNS
    is NoRouteToHostException, is PortUnreachableException -> TransportFailure.UNREACHABLE
    is ConnectException -> TransportFailure.REFUSED
    // Before the IOException arm: SSLException is an IOException, and its message would
    // otherwise be sniffed for words it does not contain.
    is SSLException -> TransportFailure.TLS
    is IOException -> classifyByMessage(t)
    else -> TransportFailure.OTHER
}
