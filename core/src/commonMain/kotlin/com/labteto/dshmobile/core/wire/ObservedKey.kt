package com.labteto.dshmobile.core.wire

import kotlin.concurrent.Volatile

/**
 * Somewhere for a trust-on-first-use client to report the key it was offered: `SHA-256` of the
 * server certificate's SubjectPublicKeyInfo, base64, the same value a pairing QR carries as its
 * fingerprint ([SpkiPin]).
 *
 * On the JVM `RelayTls.trustOnFirstUseClient` fills it in; on iOS `darwinHttpClient(observedKey = …)`
 * does. It is read after the call that used the client, so the caller can store the pin it actually
 * spoke to.
 */
class ObservedKey {
    @Volatile
    var pin: String? = null
        internal set
}
