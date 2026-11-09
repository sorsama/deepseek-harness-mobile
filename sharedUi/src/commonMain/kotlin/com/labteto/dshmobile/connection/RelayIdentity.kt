package com.labteto.dshmobile.connection

/**
 * What a successful relay pairing tells this app about an endpoint.
 *
 * Grouped rather than passed as four loose parameters because they are only ever meaningful
 * together: a fingerprint without a device id describes a relay this device cannot talk to, and a
 * device id without a scheme describes one it cannot address.
 *
 * It lives in the shared module since the Pair screen moved there: both apps hand one to their store
 * when a relay has been paired (`HostsStore.rememberHost` on Android, `UserDefaultsConnectStore` on iOS).
 */
data class RelayIdentity(
    val deviceId: String,
    val useTls: Boolean,
    val fingerprint: String?,
    val tokenExpiresAt: Long,
)
