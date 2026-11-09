package com.labteto.dshmobile.core.wire

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * JVM actual: [WsChannel] over OkHttp.
 *
 * The Android app does not come through here; it builds a [WsChannel] from its own shared client.
 * This is the same socket for common code, tests and tools, with the same trust rules as the unary
 * transport: [RelayTls.pinnedClient] for a pin, [RelayTls.trustOnFirstUseClient] for a learned key.
 */
actual fun platformMuxSocketFactory(
    url: String,
    authorization: String?,
    cookie: String?,
    connectTimeoutMs: Long,
    pinnedSpkiSha256: String?,
    observedKey: ObservedKey?,
): MuxSocketFactory {
    require(pinnedSpkiSha256 == null || pinnedSpkiSha256.isNotBlank()) { "a certificate pin cannot be blank" }
    require(pinnedSpkiSha256 == null || observedKey == null) { "a socket either enforces a pin or learns a key, not both" }
    require(pinnedSpkiSha256 == null || url.startsWith("https://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true)) {
        "a pinned relay is reached over TLS, and this address is plaintext: $url"
    }
    // The shared client's pool and dispatcher are reused; only the handshake deadline and the trust differ.
    val base = SHARED.newBuilder().connectTimeout(connectTimeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
    val client = when {
        pinnedSpkiSha256 != null -> RelayTls.pinnedClient(base, pinnedSpkiSha256)
        observedKey != null -> RelayTls.trustOnFirstUseClient(base, observedKey)
        else -> base
    }
    return { sink -> WsChannel(url, client, sink, authorization, cookie) }
}

private val SHARED: OkHttpClient by lazy { OkHttpClient() }
