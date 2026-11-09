package com.labteto.dshmobile.core.wire

/** iOS actual: [DarwinMuxSocket], the socket written on `NSURLSessionWebSocketTask`. */
actual fun platformMuxSocketFactory(
    url: String,
    authorization: String?,
    cookie: String?,
    connectTimeoutMs: Long,
    pinnedSpkiSha256: String?,
    observedKey: ObservedKey?,
): MuxSocketFactory {
    // Checked now so a bad address or trust setting surfaces at the call that wrote it.
    resolveDarwinMuxTarget(url, pinnedSpkiSha256, observedKey)
    return { sink ->
        DarwinMuxSocket(url, sink, authorization, cookie, connectTimeoutMs, pinnedSpkiSha256, observedKey)
    }
}
