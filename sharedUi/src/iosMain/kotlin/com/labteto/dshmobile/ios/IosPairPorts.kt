package com.labteto.dshmobile.ios

import com.labteto.dshmobile.connection.HostConfig
import com.labteto.dshmobile.connection.RelayIdentity
import com.labteto.dshmobile.core.wire.KtorPlainHttp
import com.labteto.dshmobile.core.wire.ObservedKey
import com.labteto.dshmobile.core.wire.PlainHttp
import com.labteto.dshmobile.core.wire.darwinHttpClient
import com.labteto.dshmobile.ui.screens.pair.PairPorts
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

/**
 * The bearer tokens of the relays this device is paired with, one per remembered relay, in the Keychain.
 *
 * Android keeps them in `RelayCredentialStore` (AES-GCM under an `AndroidKeyStore` key, the blob in
 * DataStore). The token is the whole credential for a relay, and authenticating to one is the same power
 * as a shell on the machine running the harness, so on iOS it gets the Keychain and not `NSUserDefaults`,
 * which is where the (non-secret) relay record is. Forgetting a host forgets its token.
 *
 * The same map-in-one-item shape as [KeychainSessionStore], under a service of its own.
 */
internal class KeychainRelayTokens(
    private val items: KeychainSessionStore = KeychainSessionStore(service = SERVICE),
) {
    suspend fun token(hostId: String): String? = items.cookie(hostId)

    /** The full `Authorization` value for [hostId], or null when this device holds no token for it. */
    suspend fun authorization(hostId: String): String? = token(hostId)?.let { "Bearer $it" }

    suspend fun put(hostId: String, token: String) = items.put(hostId, token)

    suspend fun remove(hostId: String) = items.remove(hostId)

    /** For the debug launch argument that resets the app. */
    fun wipe() = items.wipe()

    companion object {
        const val SERVICE = "com.labteto.dshmobile.relay-tokens"
    }
}

/**
 * [PairPorts] over the Darwin client, `NSUserDefaults` and the Keychain.
 *
 * Each call that talks to a relay gets a client of its own (`NSURLSession` per call) because the
 * three ways of trusting a relay are three different clients: one that enforces a pin, one that learns
 * the key it is shown, and the ordinary one. They are closed when the call returns.
 *
 * **Pinning.** A scanned QR hands over the relay's key and [withHttp] builds
 * `darwinHttpClient(pinnedSpkiSha256 = …)`, whose challenge handler (`pinnedChallengeHandler`) refuses any
 * leaf certificate with another key, with no fallback to system validation. A typed `https` address has no
 * key yet, so the client learns it (`learningChallengeHandler`, trust on first use) and the controller stores
 * what it saw. Neither is weakened here; a blank pin from a damaged QR becomes one that cannot match
 * anything, so it fails as a pin mismatch instead of crashing the app or being ignored.
 */
internal class IosPairPorts(
    private val store: UserDefaultsConnectStore,
    private val tokens: KeychainRelayTokens,
    private val link: IosConnectionLink,
    /** Hears the lines a script reads (`DSH_PAIR_RESULT …`); null in a normal launch. */
    private val report: ((String) -> Unit)? = null,
) : PairPorts {

    /**
     * What the relay's device list will call this phone. Since iOS 16 the system answers "iPhone" to any app
     * without the user-assigned-device-name entitlement, which still tells an operator which kind of device
     * to revoke; the person can change the name on the form.
     */
    override val deviceName: String = UIDevice.currentDevice.name.ifBlank { "iOS device" }

    override fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    override suspend fun <T> withHttp(fingerprint: String?, observed: ObservedKey?, block: suspend (PlainHttp) -> T): T {
        val client = darwinHttpClient(
            pinnedSpkiSha256 = fingerprint?.ifBlank { NEVER_MATCHES },
            observedKey = observed,
        )
        try {
            return block(KtorPlainHttp(client))
        } finally {
            client.close()
        }
    }

    override suspend fun rememberRelay(host: String, port: Int, basePath: String, relay: RelayIdentity): HostConfig =
        store.rememberRelay(host, port, basePath, relay)

    override suspend fun storeToken(hostId: String, token: String) = tokens.put(hostId, token)

    override suspend fun connect(config: HostConfig) {
        report?.invoke("DSH_PAIR_RESULT paired authority=${config.authority} tls=${config.useTls} pinned=${config.relayFingerprint != null}")
        link.connect(config)
    }

    private companion object {
        /** Not a base64 SHA-256, so no certificate has it: a damaged pin refuses every TLS connection. */
        const val NEVER_MATCHES = "-"
    }
}
