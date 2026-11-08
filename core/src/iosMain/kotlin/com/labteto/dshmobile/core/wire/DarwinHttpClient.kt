package com.labteto.dshmobile.core.wire

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin

/**
 * The Ktor client the iOS app builds [KtorRpcTransport] on: the Darwin engine, which is `NSURLSession`,
 * configured for DSH traffic. (The mux WebSocket is not Ktor's on iOS: [DarwinMuxSocket] runs its own
 * `NSURLSession` with the same pinning handlers, see [platformMuxSocketFactory].)
 *
 * Share one client across the transports for a connection the way the Android app shares its OkHttp
 * client: it owns the session, its connections and its threads. Per-call settings (the connect and
 * read deadlines, credentials) live on the transport, not here. A relay pinned by key needs its own
 * client, as a pinned OkHttp client is its own (`RelayTls.pinnedClient`).
 *
 * What differs from the JVM engines, and why it is safe:
 * - **Cookies.** Ktor's Darwin session is created with no cookie storage, so `NSURLSession`
 *   neither stores nor adds cookies and the `Cookie` header [KtorRpcTransport] sets is the only one
 *   sent. (Behind `dsh-relay` the cookie is null and a phone carries none.)
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
 * **Pinning.** [pinnedSpkiSha256] is the relay's `base64(sha256(DER SubjectPublicKeyInfo))` from
 * pairing (`RelayTls.spkiPin` on the JVM computes the same string). With it, every TLS connection
 * must present a leaf certificate with exactly that key, or it is refused — see
 * [pinnedChallengeHandler] for the rules, which are `RelayTls.pinnedClient`'s: the key is the trust
 * decision, there is no fallback to system validation, it fails closed, and the failure is reported
 * as a pin mismatch ([hasPinMismatch]) rather than a generic TLS error. Without it (plaintext, or a
 * certificate the system already trusts) the system's own evaluation applies, as with the shared
 * OkHttp client.
 *
 * **Trust on first use.** [observedKey] is for the flows that cannot pin in advance (passcode
 * pairing, probing an address before pairing): the client accepts whatever key the server presents
 * and records it in the [ObservedKey], as `RelayTls.trustOnFirstUseClient` does on the JVM. See
 * [learningChallengeHandler] for what that costs. It is a separate client from a pinned one, and
 * the two cannot be combined.
 *
 * @param pinnedSpkiSha256 the pin to enforce, or null for none.
 * @param observedKey where to record the key the server presented, or null to leave trust to the
 *   system (or to the pin).
 * @throws IllegalArgumentException when the pin is blank, which could never match and so would turn
 *   a typo into a relay that is unreachable for no visible reason, or when both a pin and an
 *   [observedKey] are given.
 */
fun darwinHttpClient(pinnedSpkiSha256: String? = null, observedKey: ObservedKey? = null): HttpClient {
    require(pinnedSpkiSha256 == null || pinnedSpkiSha256.isNotBlank()) { "a certificate pin cannot be blank" }
    require(pinnedSpkiSha256 == null || observedKey == null) { "a client either enforces a pin or learns a key, not both" }
    val mismatches = PinMismatchLog()
    val client = HttpClient(Darwin) {
        dshCarrier()
        engine {
            if (pinnedSpkiSha256 != null) {
                handleChallenge(pinnedChallengeHandler(pinnedSpkiSha256, mismatches))
            } else if (observedKey != null) {
                handleChallenge(learningChallengeHandler(observedKey))
            }
        }
    }
    client.attributes.put(DshBodyLengthCheck, false)
    if (pinnedSpkiSha256 != null) {
        // A refused challenge reaches here as a bare `NSURLErrorCancelled`. When the handler has just
        // recorded a mismatch for this host, say what it was, so the layers above can tell "the key
        // changed" from "TLS failed".
        client.plugin(HttpSend).intercept { request ->
            try {
                execute(request)
            } catch (e: Throwable) {
                val engineFailure = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH)
                    .filterIsInstance<DarwinHttpRequestException>()
                    .firstOrNull()
                if (engineFailure != null && mismatches.explains(request.url.host, engineFailure)) {
                    throw IosPinMismatchException(e)
                }
                throw e
            }
        }
    }
    return client
}

private const val MAX_CAUSE_DEPTH = 8
