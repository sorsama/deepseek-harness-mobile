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
 * iOS actual: no failure is a pin mismatch yet, because certificate pinning is not implemented on
 * iOS. [darwinHttpClient] refuses a pin outright rather than connect without checking it, so there
 * is no connection on which a mismatch could be observed. When pinning arrives, its handler is the
 * place that records a mismatch and this is where the marker is read back, the way the JVM trust
 * manager throws `PinMismatchException`.
 */
internal actual fun hasPinMismatch(t: Throwable): Boolean = false

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
 *   only as this code; Ktor keeps the HTTP status for a `401` alone, so a `403` on the mux reads
 *   here as "not a harness". The unary calls, which do carry their status, are unaffected.)
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
    if (underlying?.domain == NSPOSIXErrorDomain) {
        when (underlying.code.toInt()) {
            ECONNREFUSED -> return TransportFailure.REFUSED
            EHOSTUNREACH, ENETUNREACH, ENETDOWN, EHOSTDOWN -> return TransportFailure.UNREACHABLE
            ETIMEDOUT -> return TransportFailure.TIMEOUT
        }
    }
    return TransportFailure.REFUSED
}
