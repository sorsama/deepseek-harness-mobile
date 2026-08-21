package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.dto.HostDescription

/**
 * What answered — or did not — at one `host:port`.
 *
 * [DiscoveryEngine.probe] used to return `HostDescription?`, which made a firewall, a loopback-only
 * bind, a trust-fence rejection and a typo the same event. They need opposite instructions, so the
 * probe keeps the distinction and the connect screen spends it.
 */
sealed interface ProbeOutcome {

    /** A harness answered `host.describe`. */
    data class Reachable(val description: HostDescription) : ProbeOutcome

    /** HTTP 403 — the harness is there and its `Host` trust fence refused this address. */
    data object TrustFence : ProbeOutcome

    /**
     * HTTP 403 from a relay: this device has no credential it will accept any more.
     *
     * The same status as [TrustFence], and the relay answers it for a missing, expired and revoked
     * token alike (never 401 — deliberately, so a client that already reads 403 as "reached it, it
     * refused me" keeps a usable hint). What separates the two here is local knowledge: the app
     * knows whether it ever paired with this address.
     */
    data object PairingRequired : ProbeOutcome

    /**
     * The relay's public key is not the one pinned when this device paired.
     *
     * Worth its own outcome because the honest reading is ambiguous and the user is the only one who
     * can resolve it: the relay regenerates its certificate — with a new key — whenever the set of
     * addresses it covers changes, so a harness laptop that moved networks produces exactly this,
     * and so would somebody else answering on that address.
     */
    data object CertificateChanged : ProbeOutcome

    /** The host answered the network but nothing listens on the port. */
    data object Refused : ProbeOutcome

    /** Nothing answered at all: the packets were dropped rather than refused. */
    data object Timeout : ProbeOutcome

    /** The name did not resolve. */
    data object DnsFailure : ProbeOutcome

    /** No route to the host — usually a different network entirely. */
    data object Unreachable : ProbeOutcome

    /** Something is listening, but it does not speak the harness protocol. */
    data object NotAHarness : ProbeOutcome

    /**
     * The socket opened but the TLS handshake failed — a certificate this phone does not trust,
     * or `https://` aimed at a plain-HTTP server.
     */
    data object TlsFailure : ProbeOutcome

    /** Anything else; [detail] is the carrier's own words, for the fallback message. */
    data class Other(val detail: String) : ProbeOutcome
}

/** Connect/read budgets for a probe. */
data class ProbeTimeouts(val connectMs: Long, val readMs: Long) {
    companion object {
        /**
         * The first pass of a sweep: a bare TCP connect, nothing more.
         *
         * On a /24 almost every address is dead or refuses instantly, and both answers arrive in
         * well under this budget. Only the few that open a socket go on to pay for HTTP, so the
         * deadline that decides the length of a scan is this one — not [Sweep].
         */
        val Knock = ProbeTimeouts(connectMs = 300, readMs = 300)

        /** Sweeping 254 addresses: fail fast, most of them are nothing. */
        val Sweep = ProbeTimeouts(connectMs = 700, readMs = 1_500)

        /**
         * One address the user typed: worth more patience than a sweep entry, but still bounded —
         * this runs behind a progress indicator, not a frozen button.
         */
        val Manual = ProbeTimeouts(connectMs = 2_500, readMs = 4_000)
    }
}
