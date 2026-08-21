package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.dto.HostDescription
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Enumeration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether [target] sits in the same /24 as any of [localIps].
 *
 * Free function so it is testable without a device: the sweep only ever looks at the phone's own
 * /24, so an address outside it can be rejected instantly with a message that also explains why
 * scanning found nothing. A non-literal host (a name) is not judged here — it cannot be.
 */
internal fun sameSubnet(target: String, localIps: List<String>): Boolean {
    val targetParts = target.split('.')
    if (targetParts.size != 4 || targetParts.any { part -> part.toIntOrNull()?.takeIf { it in 0..255 } == null }) {
        return true // Not an IPv4 literal — nothing to compare, so do not claim a mismatch.
    }
    if (localIps.isEmpty()) return true
    val targetPrefix = targetParts.take(3)
    return localIps.any { it.split('.').take(3) == targetPrefix }
}

/**
 * Finds DeepSeek Harness instances on the local Wi-Fi.
 *
 * The harness advertises nothing — no mDNS, no broadcast — so discovery is an active sweep of the
 * device's own IPv4 subnet. What keeps that affordable is asking three progressively more expensive
 * questions in order (see [scan]): does anything accept a socket, is it a harness, and will it talk
 * to us. Collapsing them into one `host.describe` per address, as this used to, meant every dead
 * address on a /24 paid an HTTP timeout.
 *
 * Worth knowing when reading a scan that finds nothing: the harness binds `127.0.0.1` by default
 * and refuses `--host 0.0.0.0` outright, so LAN serving is something its operator has to opt into.
 */
@Singleton
class DiscoveryEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    /** This device's non-loopback IPv4 addresses (e.g. Wi-Fi). */
    fun localIpv4s(): List<String> = runCatching {
        val result = mutableListOf<String>()
        val interfaces: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) result.add(addr.hostAddress ?: "")
            }
        }
        result.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** /24 candidates for a device IPv4, e.g. 192.168.1.1..254 (host itself excluded). */
    fun subnetCandidates(ip: String): List<String> {
        val parts = ip.split('.')
        if (parts.size != 4) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }.filter { it != ip }
    }

    /**
     * Probe one authority; null when it is not a harness.
     *
     * [timeouts] defaults to the sweep budget, which is right for a candidate pulled out of a /24
     * and wrong for a host someone named — pass [ProbeTimeouts.Manual] for those. (Until the
     * transport learned to honour these, every probe silently used 30s, so a named host was
     * accidentally patient and a sweep accidentally slow.)
     */
    suspend fun probe(
        host: String,
        port: Int,
        timeouts: ProbeTimeouts = ProbeTimeouts.Sweep,
        useTls: Boolean = false,
    ): HostDescription? =
        (probeOutcome(host, port, timeouts, useTls = useTls) as? ProbeOutcome.Reachable)?.description

    /**
     * Probe one authority and keep the reason it failed.
     *
     * With [preflight] the connect deadline is enforced by a raw socket before the HTTP call. That
     * is not redundant: Android can surface a kernel connect timeout as a plain `ConnectException`
     * naming ETIMEDOUT, which blurs "refused" into "timed out" — and those two are exactly what
     * separates "the firewall is dropping this" from "the harness is still bound to loopback". The
     * sweep skips it (254 extra sockets to learn the same thing OkHttp will report anyway); the one
     * address a user typed is worth the ~5ms.
     */
    suspend fun probeOutcome(
        host: String,
        port: Int,
        timeouts: ProbeTimeouts = ProbeTimeouts.Sweep,
        preflight: Boolean = false,
        useTls: Boolean = false,
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        if (preflight) {
            preflight(host, port, timeouts.connectMs)?.let { return@withContext it }
        }
        val client = DshApiClient(
            // Timeouts go through the transport's own parameters: it rebuilds the client it is
            // handed, so anything applied to the builder here would be silently replaced by the 30s
            // default — which is why this probe used to be able to block for thirty seconds.
            transport = OkHttpRpcTransport(
                baseUrl = harnessBaseUrl(host, port, useTls),
                client = okHttpClient,
                connectTimeoutMs = timeouts.connectMs,
                readTimeoutMs = timeouts.readMs,
            ),
            wsFactory = { _, _ -> throw UnsupportedOperationException("probe does not open streams") },
        )
        when (val result = client.hostDescribe()) {
            is RpcResult.Ok -> ProbeOutcome.Reachable(result.value)
            is RpcResult.Err -> when (TransportFailures.of(result.error)) {
                TransportFailure.TRUST_FENCE -> ProbeOutcome.TrustFence
                TransportFailure.REFUSED -> ProbeOutcome.Refused
                TransportFailure.TIMEOUT -> ProbeOutcome.Timeout
                TransportFailure.DNS -> ProbeOutcome.DnsFailure
                TransportFailure.UNREACHABLE -> ProbeOutcome.Unreachable
                TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS -> ProbeOutcome.NotAHarness
                TransportFailure.TLS -> ProbeOutcome.TlsFailure
                TransportFailure.OTHER, null -> ProbeOutcome.Other(result.error.message)
            }
        }
    }

    /**
     * One raw TCP connect with our own deadline, so a drop is distinguishable from a refusal.
     * Returns null when the socket opened — the HTTP call then decides what is listening.
     */
    private fun preflight(host: String, port: Int, connectMs: Long): ProbeOutcome? = try {
        // Resolve explicitly: InetSocketAddress(String, Int) yields an *unresolved* address on a
        // DNS failure rather than throwing, which would surface later as a confusing socket error.
        val address = InetAddress.getByName(host)
        Socket().use { it.connect(InetSocketAddress(address, port), connectMs.toInt()) }
        null
    } catch (e: SocketTimeoutException) {
        ProbeOutcome.Timeout
    } catch (e: UnknownHostException) {
        ProbeOutcome.DnsFailure
    } catch (e: NoRouteToHostException) {
        ProbeOutcome.Unreachable
    } catch (e: ConnectException) {
        ProbeOutcome.Refused
    } catch (e: IOException) {
        when (TransportFailures.classify(e)) {
            TransportFailure.TIMEOUT -> ProbeOutcome.Timeout
            TransportFailure.REFUSED -> ProbeOutcome.Refused
            TransportFailure.UNREACHABLE -> ProbeOutcome.Unreachable
            else -> ProbeOutcome.Other(e.message ?: "connection failed")
        }
    }

    /**
     * True when [host] is an IPv4 literal in one of this device's own /24s.
     *
     * Suspending because enumerating interfaces is a syscall walk, and this runs on the tap that
     * starts a connection — not somewhere a stall is acceptable.
     */
    suspend fun isOnLocalSubnet(host: String): Boolean =
        withContext(Dispatchers.IO) { sameSubnet(host, localIpv4s()) }

    /** This device's own /24 as a label, e.g. `192.168.1.x`; null when it has no IPv4. */
    suspend fun localSubnetLabel(): String? = withContext(Dispatchers.IO) {
        localIpv4s().firstOrNull()
            ?.split('.')
            ?.takeIf { it.size == 4 }
            ?.let { "${it[0]}.${it[1]}.${it[2]}.x" }
    }

    /**
     * Sweep the subnet(s) of this device on [ports], probing concurrently.
     *
     * Two stages, cheap one first. A bare TCP knock rejects the ~253 addresses of a /24 that are
     * nothing, and only the handful that open a socket pay for `host.describe`. The previous shape
     * — a full HTTP RPC at every address, ports tried in series, and a `chunked(32).awaitAll()`
     * barrier that made each batch cost its slowest member — ran for the better part of a minute on
     * one port and minutes across several. This costs roughly `ceil(pairs / CONCURRENCY) × 300ms`
     * plus a few round trips.
     *
     * [onProgress] reports `(probed, total)` per knock, and [onFound] fires the moment a host is
     * confirmed rather than making the caller wait for the whole sweep — the harness people are
     * looking for is usually the first one found.
     */
    suspend fun scan(
        ports: List<Int>,
        onProgress: (probed: Int, total: Int) -> Unit = { _, _ -> },
        onFound: (DiscoveredHost) -> Unit = {},
    ): List<DiscoveredHost> = supervisorScope {
        val subnets = localIpv4s()
        if (subnets.isEmpty()) return@supervisorScope emptyList()
        val portsSafe = ports.ifEmpty { listOf(DEFAULT_PORT) }
            // The default port is the overwhelmingly likely one, and stage 1 is bounded by the
            // slowest pair in the last batch — so it belongs in the first batch, not the last.
            .sortedBy { if (it == DEFAULT_PORT) 0 else 1 }
        val candidates = subnets.flatMap { subnetCandidates(it) }.distinct().let(::byLikelihood)
        val pairs = candidates.flatMap { ip -> portsSafe.map { port -> ip to port } }

        val probed = AtomicInteger(0)
        val discovered = CopyOnWriteArrayList<DiscoveredHost>()
        onProgress(0, pairs.size)

        // Every pair gets a coroutine; [sweepDispatcher] is what decides how many are in flight.
        // They are cheap while parked, and one bound in one place beats a thread cap and a
        // semaphore that have to be kept in agreement.
        pairs.map { (ip, port) ->
            async(sweepDispatcher) {
                // Stage 1 — knock. Everything below only runs for a socket that opened.
                val open = runCatching { preflight(ip, port, ProbeTimeouts.Knock.connectMs) }
                    .getOrDefault(ProbeOutcome.Timeout) == null
                onProgress(probed.incrementAndGet(), pairs.size)
                if (!open) return@async
                // Stage 2 — describe. A trust-fence refusal counts as found: only a harness answers
                // 403 to this call, so it identifies one just as well as a description does, and it
                // is the most recoverable thing a sweep can turn up.
                val outcome = runCatching { probeOutcome(ip, port) }
                    .getOrDefault(ProbeOutcome.Other("probe failed"))
                val found = when (outcome) {
                    is ProbeOutcome.Reachable -> DiscoveredHost(ip, port, outcome.description)
                    ProbeOutcome.TrustFence -> DiscoveredHost(ip, port, description = null)
                    else -> return@async
                }
                discovered.add(found)
                onFound(found)
            }
        }.awaitAll()

        // Trusted hosts first: a card you can actually connect to outranks one that needs the
        // harness reconfigured before it will answer.
        discovered.sortedByDescending { it.trusted }
    }

    /**
     * Sweep order: the router first, then everything else ascending.
     *
     * Only worth the line because [onFound] streams — a host found in the first batch is on screen
     * while the rest of the /24 is still being knocked, and `.1` is where a self-hosted anything is
     * most often found.
     */
    private fun byLikelihood(candidates: List<String>): List<String> =
        candidates.sortedBy { if (it.endsWith(".1")) 0 else 1 }

    private companion object {
        const val DEFAULT_PORT = 3080

        /**
         * Concurrent knocks, and so the width of the sweep.
         *
         * Above [Dispatchers.IO]'s own 64, which is why it goes through `limitedParallelism` — that
         * is the documented way to exceed the IO cap. A knock is almost entirely connect latency
         * rather than work, so the number that matters is how many sockets may be waiting at once,
         * not how many cores there are.
         */
        const val CONCURRENCY = 128
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sweepDispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)
}
