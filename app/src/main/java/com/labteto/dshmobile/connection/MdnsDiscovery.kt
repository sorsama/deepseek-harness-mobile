package com.labteto.dshmobile.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds `dsh-relay` listeners by their mDNS advertisement.
 *
 * A relay publishes `_dsh._tcp` with everything a client needs before it connects: the port, whether
 * the primary listener terminates TLS, and the key to pin. That removes the subnet sweep entirely —
 * a browse answers in a second or two where knocking 254 addresses takes tens of them, and it finds
 * a relay on a subnet the sweep would never look at.
 *
 * Nothing depends on it. The relay's `mdns` flag can be off, some networks drop multicast, and
 * Android's own resolver is unreliable enough that treating a browse as authoritative would make
 * discovery worse than the sweep it replaces. So this reports what it finds and the caller falls
 * back.
 *
 * Only IPv4 results are kept, matching [DiscoveryEngine] — the rest of the app builds authorities as
 * `host:port`, which an IPv6 literal cannot be written as without brackets.
 */
@Singleton
class MdnsDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Browse for [windowMs], reporting each relay as it resolves.
     *
     * The window is the whole budget: mDNS has no "that is all of them" and a browse left running
     * would keep the multicast socket and its wake-ups alive for as long as the screen is open.
     *
     * @param onFound fires per relay, so a card can appear before the window closes.
     * @return every distinct relay resolved inside the window.
     */
    suspend fun browse(
        windowMs: Long = DEFAULT_WINDOW_MS,
        onFound: (DiscoveredHost) -> Unit = {},
    ): List<DiscoveredHost> = withContext(Dispatchers.IO) {
        val nsd = runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
            ?: return@withContext emptyList()
        val found = CopyOnWriteArrayList<DiscoveredHost>()
        // Resolution is serialized behind this: on several Android versions a second concurrent
        // resolveService fails the first with FAILURE_ALREADY_ACTIVE rather than queueing.
        val resolving = Mutex()
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.let { queue.trySend(it) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                queue.close()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
        }

        val started = runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.isSuccess
        if (!started) return@withContext emptyList()

        try {
            withTimeoutOrNull(windowMs) {
                supervisorScope {
                    launch {
                        for (service in queue) {
                            val relay = resolving.withLock { resolve(nsd, service) } ?: continue
                            if (found.none { it.authority == relay.authority }) {
                                found.add(relay)
                                onFound(relay)
                            }
                        }
                    }
                }
            }
        } finally {
            queue.close()
            // Both are best-effort: an already-stopped discovery throws IllegalArgumentException,
            // and a browse that outlives its window is worse than a noisy stop.
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        found.toList()
    }

    /** Resolve one advertisement, or null when it does not resolve or is not a usable relay. */
    private suspend fun resolve(nsd: NsdManager, service: NsdServiceInfo): DiscoveredHost? {
        val resolved = withTimeoutOrNull(RESOLVE_MS) { awaitResolve(nsd, service) } ?: return null
        return asRelay(resolved)
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitResolve(nsd: NsdManager, service: NsdServiceInfo): NsdServiceInfo? {
        // `resolveService` is deprecated for `registerServiceInfoCallback` on API 34+. The
        // replacement does not exist below 34 and this app supports 26, so the deprecated call is
        // the one that works everywhere; there is nothing here that the newer API would do better.
        val answer = Channel<NsdServiceInfo?>(Channel.CONFLATED)
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                answer.trySend(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                answer.trySend(serviceInfo)
            }
        }
        // Only the call is guarded: `resolveService` throws when a resolve is already in flight,
        // which the mutex above should prevent but which is a crash if it ever slips. The receive is
        // deliberately outside, so the enclosing timeout's cancellation propagates instead of being
        // swallowed as a null result and leaving this coroutine running in a cancelled state.
        if (runCatching { nsd.resolveService(service, listener) }.isFailure) return null
        return answer.receive()
    }

    /**
     * Read a resolved advertisement as a relay, or null when it is not one this client can use.
     *
     * The TXT records are checked rather than assumed: `_dsh._tcp` is this ecosystem's service type,
     * not this plugin's, so an advertisement without `relay=dsh-relay` is somebody else's and an
     * unrecognised `v` may mean fields whose meaning this build would get wrong.
     */
    private fun asRelay(info: NsdServiceInfo): DiscoveredHost? {
        val txt = info.attributes.orEmpty()
        fun record(key: String): String? = txt[key]?.let { String(it, Charsets.UTF_8) }
        if (record("relay") != RELAY_SERVICE) return null
        if (record("v") != SUPPORTED_VERSION) return null

        @Suppress("DEPRECATION")
        val address = info.host as? Inet4Address ?: return null
        val host = address.hostAddress ?: return null
        val port = info.port.takeIf { it in 1..65535 } ?: return null

        val tls = record("tls") ?: TLS_OFF
        val pin = record("pin")?.takeIf { it.isNotBlank() }
        return DiscoveredHost(
            host = host,
            port = port,
            // A relay never answers `host.describe` to an unpaired device, so there is nothing to
            // cache here. The card says "pair with this" rather than "connect", which is the truth.
            description = null,
            useTls = tls != TLS_OFF,
            fingerprint = pin,
            isRelay = true,
        )
    }

    private companion object {
        /** DNS-SD type the relay publishes under. NsdManager wants the trailing dot. */
        const val SERVICE_TYPE = "_dsh._tcp."

        /** TXT `relay` value that identifies this plugin rather than some other `_dsh._tcp` service. */
        const val RELAY_SERVICE = "dsh-relay"

        /** TXT `v` this build understands. */
        const val SUPPORTED_VERSION = "1"

        /** TXT `tls` value meaning the primary listener serves plaintext. */
        const val TLS_OFF = "off"

        /** How long a browse runs before it is called done. */
        const val DEFAULT_WINDOW_MS = 4_000L

        /** Per-advertisement resolve budget, so one silent responder cannot eat the window. */
        const val RESOLVE_MS = 2_000L
    }
}
