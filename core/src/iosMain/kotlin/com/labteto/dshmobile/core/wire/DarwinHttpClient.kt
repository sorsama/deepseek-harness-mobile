package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * The Ktor client the iOS app builds [KtorRpcTransport] and [KtorMuxSocket] on: the Darwin engine,
 * which is `NSURLSession` and `NSURLSessionWebSocketTask`, configured for DSH traffic.
 *
 * Share one client across the transports for a connection the way the Android app shares its OkHttp
 * client: it owns the session, its connections and its threads. Per-call settings (the connect and
 * read deadlines, credentials) live on the transport, not here.
 *
 * What differs from the JVM engines, and why it is safe:
 * - **Cookies.** Ktor's Darwin session already turns the shared cookie storage off; this also tells
 *   each request not to handle cookies, so the `Cookie` header [KtorRpcTransport] sets is the only
 *   one sent. (Behind `dsh-relay` the cookie is null and a phone carries none.)
 * - **Redirects** are refused by the engine's session delegate before Ktor sees them, and
 *   [dshCarrier] switches Ktor's own following off too.
 * - **`Host`.** `NSURLSession` documents `Host` among the headers it manages. The transport still
 *   sets it, because where the system honours it the value is the one OkHttp sends, and where it
 *   does not, the system derives `host[:port]` from the URL by the same rule (default port omitted,
 *   IPv6 literal bracketed) — either way the harness's fence reads the right thing.
 * - **Deadlines.** `NSURLRequest.timeoutInterval` is one idle timeout that also covers connecting,
 *   so [KtorRpcTransport]'s separate connect and read deadlines collapse into the read one here.
 *   The mux socket sets no read deadline at all.
 * - **Body length.** `NSURLSession` fails a body that ends short of its length itself and may
 *   decompress a gzip answer while leaving the compressed `Content-Length` in the headers, so the
 *   transport's own length check is switched off for this client ([DshBodyLengthCheck]).
 *
 * Certificate pinning for `dsh-relay` over TLS is not implemented on iOS yet. [pinnedSpkiSha256]
 * exists so the refusal is explicit: a pin that cannot be enforced is an error, never a connection
 * that quietly goes unpinned.
 *
 * @param pinnedSpkiSha256 the relay's `base64(sha256(DER SubjectPublicKeyInfo))` from pairing (see
 *   `RelayTls.spkiPin`), or null for plaintext and publicly trusted TLS.
 * @throws UnsupportedOperationException when a pin is passed, until pinning is implemented.
 */
fun darwinHttpClient(pinnedSpkiSha256: String? = null): HttpClient {
    if (pinnedSpkiSha256 != null) {
        throw UnsupportedOperationException(
            "certificate pinning is not implemented on iOS yet; refusing to build a client that would " +
                "connect to a pinned relay without checking its key",
        )
    }
    val client = HttpClient(Darwin) {
        dshCarrier()
        engine {
            configureRequest {
                setHTTPShouldHandleCookies(false)
            }
        }
    }
    client.attributes.put(DshBodyLengthCheck, false)
    return client
}
