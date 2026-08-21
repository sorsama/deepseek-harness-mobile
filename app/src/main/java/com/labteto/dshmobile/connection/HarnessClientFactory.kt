package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RelayTls
import com.labteto.dshmobile.core.wire.WsDownlink
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a [DshApiClient] is built.
 *
 * Three facts have to agree for a call to reach a relay at all — the scheme, the certificate pin and
 * the bearer token — and they have to agree across the unary transport *and* both WebSocket
 * upgrades, because the connection loop needs all three to succeed inside one 3000ms generation. A
 * relay refuses an upgrade that arrives without the header, and the loop can only report that as a
 * stream that would not open. Splitting the assembly across the manager and the discovery engine is
 * how one of the three quietly goes missing, so it happens here or nowhere.
 */
@Singleton
class HarnessClientFactory @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val credentials: RelayCredentialStore,
) {
    /**
     * Pinned clients, one per fingerprint.
     *
     * Each carries its own `SSLContext` and connection pool, so building one per request would
     * throw away every kept-alive connection — including the two downlink sockets. There is one
     * relay in play at a time and the key is a base64 hash, so the map never grows meaningfully.
     */
    private val pinned = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * The HTTP client to reach [fingerprint]'s relay with, or the shared one when nothing is pinned.
     *
     * A null fingerprint means plaintext or a certificate the platform already trusts; both are
     * served correctly by the default trust store.
     */
    fun httpClient(fingerprint: String?): OkHttpClient =
        if (fingerprint == null) okHttpClient
        else pinned.getOrPut(fingerprint) { RelayTls.pinnedClient(okHttpClient, fingerprint) }

    /** The `Authorization` value for [config], or null when it is not a paired relay. */
    suspend fun authorizationFor(config: HostConfig): String? =
        if (config.isRelay) credentials.authorization(config.id) else null

    /**
     * A client for [config], carrying whatever credential and pin that endpoint needs.
     *
     * [timeouts] is for probes; the live connection takes the transport's own 30s defaults, because
     * a long `session.history` on a big session is not a stalled request.
     */
    suspend fun clientFor(config: HostConfig, timeouts: ProbeTimeouts? = null): DshApiClient {
        val http = httpClient(config.relayFingerprint)
        val authorization = authorizationFor(config)
        val base = config.baseUrl
        return DshApiClient(
            transport = OkHttpRpcTransport(
                baseUrl = base,
                client = http,
                connectTimeoutMs = timeouts?.connectMs ?: DEFAULT_TIMEOUT_MS,
                readTimeoutMs = timeouts?.readMs ?: DEFAULT_TIMEOUT_MS,
                authorization = authorization,
            ),
            wsFactory = { path, sink -> WsDownlink("$base$path", http, sink, authorization) },
        )
    }

    /**
     * A client for an address nothing is remembered about yet — the LAN sweep and the manual field.
     *
     * Deliberately unauthenticated: an address that has not been paired has no credential to send,
     * and a relay answers such a probe with the 403 that routes the user to pairing.
     */
    fun anonymousClient(baseUrl: String, timeouts: ProbeTimeouts): DshApiClient = DshApiClient(
        transport = OkHttpRpcTransport(
            baseUrl = baseUrl,
            client = okHttpClient,
            connectTimeoutMs = timeouts.connectMs,
            readTimeoutMs = timeouts.readMs,
        ),
        wsFactory = { _, _ -> throw UnsupportedOperationException("probe does not open streams") },
    )

    private companion object {
        /** The transport's own default, restated so a null [ProbeTimeouts] is explicit rather than magic. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
