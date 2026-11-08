package com.labteto.dshmobile.core.wire

/**
 * The [MuxSocketFactory] for the platform's own WebSocket carrier, which is how an app layer that is
 * not tied to one platform gets a mux socket without naming a class:
 *
 * - **iOS**: `DarwinMuxSocket`, written directly on `NSURLSessionWebSocketTask`. It is what the iOS
 *   app rides on. The Ktor WebSocket on the Darwin engine (`KtorMuxSocket`) loses a refused upgrade's
 *   status and caps messages at 1 MiB there, which is why it is not the iOS carrier.
 * - **JVM**: `WsChannel` over OkHttp, the carrier the Android app has always run. The Android app
 *   builds its sockets from its own shared `OkHttpClient` (`HarnessClientFactory`) and does not call
 *   this; the JVM actual exists so common code, tests and tools get the shipping carrier by one name.
 *
 * The contract is [MuxSocket]'s and [WsChannelSink]'s. [url] may be `http(s)://` as
 * `resolveHarnessUrl` produces, or `ws(s)://`.
 *
 * **Trust.** [pinnedSpkiSha256] is the relay's `base64(sha256(DER SubjectPublicKeyInfo))` from pairing:
 * the key is the trust decision, with no fallback to system validation, exactly as for the unary
 * transport (`RelayTls.pinnedClient`, `darwinHttpClient(pinnedSpkiSha256 = …)`). [observedKey] is trust
 * on first use for the flows that cannot pin in advance, and records what the server presented. The two
 * cannot be combined, and a pin needs a TLS address. With neither, the system's own trust applies.
 *
 * [connectTimeoutMs] bounds the handshake; there is no read deadline once the socket is open (the host
 * pings and the platform answers). Zero or less means no deadline.
 *
 * @throws IllegalArgumentException (here, not later inside the connection loop that builds a socket for
 *   every generation) for an address that is not a harness's, a blank pin, a pin together with an
 *   [observedKey], or a pin on a plaintext address.
 */
expect fun platformMuxSocketFactory(
    url: String,
    authorization: String? = null,
    cookie: String? = null,
    connectTimeoutMs: Long = 30_000,
    pinnedSpkiSha256: String? = null,
    observedKey: ObservedKey? = null,
): MuxSocketFactory
