package com.labteto.dshmobile.core.wire

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.cinterop.UnsafeNumber
import kotlinx.io.IOException
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorAppTransportSecurityRequiresSecureConnection
import platform.Foundation.NSURLErrorBadServerResponse
import platform.Foundation.NSURLErrorCallIsActive
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorCannotParseResponse
import platform.Foundation.NSURLErrorClientCertificateRejected
import platform.Foundation.NSURLErrorClientCertificateRequired
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSPOSIXErrorDomain
import platform.Foundation.NSUnderlyingErrorKey
import platform.posix.ECONNREFUSED
import platform.posix.EHOSTDOWN
import platform.posix.EHOSTUNREACH
import platform.posix.ENETDOWN
import platform.posix.ENETUNREACH
import platform.posix.ETIMEDOUT

/**
 * iOS actual: whether [t] or anything it wraps is an [IosPinMismatchException].
 *
 * The pin check lives in the Darwin challenge handler ([pinnedChallengeHandler]); the engine reports
 * a refused challenge only as a cancellation, and [darwinHttpClient] turns that into this marker when
 * the handler has just recorded a mismatch. Read back here the way the JVM walks its cause chain for
 * `PinMismatchException`, with the same bound: nothing legitimate nests this deep and a cyclic chain
 * must not hang.
 */
internal actual fun hasPinMismatch(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.take(MAX_PIN_CAUSE_DEPTH).any { it is IosPinMismatchException }

private const val MAX_PIN_CAUSE_DEPTH = 8

/**
 * A mux socket on `NSURLSessionWebSocketTask` that failed without an HTTP answer to explain it
 * (refused, unreachable, dropped, no route, its own handshake deadline, ...).
 *
 * [verdict] is decided where the `NSError` is read, by [classifyNSError], so classification here is
 * a lookup and not a second guess. [origin] is the operating system's own error, kept for logs and
 * for tests; its description is localised by the device language and is never classified on.
 */
internal class DarwinSocketException(
    val verdict: TransportFailure,
    message: String,
    val origin: NSError? = null,
) : IOException(message)

/**
 * iOS actual: classify by what the Darwin engine actually throws.
 *
 * Ktor's Darwin engine hands back one of three things for a failed `NSURLSession` task:
 *
 * - [SocketTimeoutException], for `NSURLErrorTimedOut` (the engine converts that one code itself);
 * - [DarwinHttpRequestException], wrapping the raw `NSError` for every other code, whose
 *   `domain`/`code` are the facts to switch on — the message is a localized sentence and is never
 *   read;
 * - a plain [IOException] from the layers around it (a body that ended early, a closed channel),
 *   which only the shared message rule can say anything about.
 *
 * Anything the codes do not settle is [TransportFailure.OTHER] rather than a guess, as on every
 * platform: a wrong verdict sends a person to reconfigure the wrong thing.
 */
internal actual fun classifyPlatformFailure(t: Throwable): TransportFailure = when (t) {
    // `DarwinMuxSocket` has already read the `NSError` (or its own deadline) and says what it was.
    is DarwinSocketException -> t.verdict
    is SocketTimeoutException, is ConnectTimeoutException, is HttpRequestTimeoutException -> TransportFailure.TIMEOUT
    is DarwinHttpRequestException -> classifyNSError(t.origin)
    is IOException -> classifyByMessage(t)
    else -> TransportFailure.OTHER
}

/**
 * The verdict for one `NSError`.
 *
 * Only `NSURLErrorDomain` is read. The mapping, and what each code means on Darwin:
 *
 * - `TimedOut` (-1001): the request's idle timeout passed with no data — dropped, not refused.
 * - `CannotFindHost` / `DNSLookupFailed`: the name did not resolve.
 * - `CannotConnectToHost` (-1004): the host answered the network and the connection was not made.
 *   Darwin uses this one code for refused, unreachable and several others; the underlying POSIX
 *   error, when present, says which, and a bare one is read as refused, which is by far the
 *   commonest cause.
 * - `NotConnectedToInternet` and the airplane/roaming/call-active family: no usable route.
 * - `SecureConnectionFailed` and the server/client certificate codes, and ATS refusing a plaintext
 *   connection: the TCP connection opened and TLS did not survive it.
 * - `BadServerResponse` / `CannotParseResponse`: something answered and it was not valid HTTP — or,
 *   for a WebSocket upgrade, was not `101`. (`NSURLSessionWebSocketTask` reports a rejected upgrade
 *   only as this code. `DarwinMuxSocket` therefore reads the HTTP status off the task's response and
 *   reports a rejected upgrade as an `RpcTransportException`, which is classified by its status
 *   before this function is asked; only a Ktor WebSocket on the Darwin engine ends up here with the
 *   bare code.)
 * - `NetworkConnectionLost` (-1005) and `Cancelled` (-999) say nothing about *why*: [TransportFailure.OTHER].
 */
@OptIn(UnsafeNumber::class)
internal fun classifyNSError(error: NSError): TransportFailure {
    if (error.domain != NSURLErrorDomain) return TransportFailure.OTHER
    return when (error.code) {
        NSURLErrorTimedOut -> TransportFailure.TIMEOUT
        NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed -> TransportFailure.DNS
        NSURLErrorCannotConnectToHost -> classifyConnectFailure(error)
        NSURLErrorNotConnectedToInternet,
        NSURLErrorInternationalRoamingOff,
        NSURLErrorCallIsActive,
        NSURLErrorDataNotAllowed,
        -> TransportFailure.UNREACHABLE
        NSURLErrorSecureConnectionFailed,
        NSURLErrorServerCertificateHasBadDate,
        NSURLErrorServerCertificateUntrusted,
        NSURLErrorServerCertificateHasUnknownRoot,
        NSURLErrorServerCertificateNotYetValid,
        NSURLErrorClientCertificateRejected,
        NSURLErrorClientCertificateRequired,
        NSURLErrorAppTransportSecurityRequiresSecureConnection,
        -> TransportFailure.TLS
        NSURLErrorBadServerResponse, NSURLErrorCannotParseResponse -> TransportFailure.NOT_A_HARNESS
        else -> TransportFailure.OTHER
    }
}

/** Refine `CannotConnectToHost` with the POSIX error CFNetwork attaches as the underlying cause. */
private fun classifyConnectFailure(error: NSError): TransportFailure {
    val underlying = error.userInfo[NSUnderlyingErrorKey] as? NSError
    if (underlying != null && underlying.domain == NSPOSIXErrorDomain) {
        when (underlying.code.toInt()) {
            ECONNREFUSED -> return TransportFailure.REFUSED
            EHOSTUNREACH, ENETUNREACH, ENETDOWN, EHOSTDOWN -> return TransportFailure.UNREACHABLE
            ETIMEDOUT -> return TransportFailure.TIMEOUT
        }
    }
    return TransportFailure.REFUSED
}
