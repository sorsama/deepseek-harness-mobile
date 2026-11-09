package com.labteto.dshmobile.ui.screens.connect

import com.labteto.dshmobile.connection.ProbeOutcome
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.TransportFailure
import com.labteto.dshmobile.core.wire.TransportFailures

/**
 * Why a connection attempt did not succeed, at the level a person can act on.
 *
 * One step above [ProbeOutcome]: the probe knows what the socket did, this knows what to tell
 * someone standing between a phone and a computer. Deliberately free of Android imports so the whole
 * mapping is unit-testable — the app's tests are plain JVM, with no Robolectric.
 */
sealed interface ConnectFailure {

    /** The address or port was not usable as typed. */
    data object InvalidInput : ConnectFailure

    /** The address is not on this phone's own /24, so nothing here can reach it. */
    data class DifferentSubnet(val localPrefix: String?) : ConnectFailure

    /** Nothing answered — dropped packets. Firewall, or a router isolating wireless clients. */
    data object Timeout : ConnectFailure

    /** Actively refused — the computer is there, the harness is not listening on that port. */
    data object Refused : ConnectFailure

    /** The harness answered and its `Host` trust fence rejected this address. */
    data object TrustFence : ConnectFailure

    /**
     * The harness answered and has no browser session for this client (HTTP 401).
     *
     * Harness 0.1.2 authenticates its whole `/api` surface, so a device that has not exchanged a
     * launch token is refused before any method runs. Separate from [TrustFence] because the fix
     * is on the phone (exchange a token from the harness's startup URL) rather than in the
     * harness's trusted-host list.
     */
    data object Unauthenticated : ConnectFailure

    /**
     * A relay answered and refused this device's credential.
     *
     * The same HTTP 403 as [TrustFence] — the relay never answers 401, on purpose — so the two are
     * told apart by what the app already knows about the address rather than by the wire. It covers
     * "never paired", "token expired" and "operator revoked this device" alike, because all three
     * have the same fix and the relay deliberately does not distinguish them to an unauthenticated
     * caller.
     */
    data object PairingRequired : ConnectFailure

    /**
     * A relay is in front of a harness that answered 401 to the relay's own request.
     *
     * Not the same thing as [PairingRequired], although both arrive by way of a relay: this device
     * holds a credential the relay accepted, and the refusal is one hop further upstream. A relay
     * proxies `/api` with a browser session it mints from the harness's own signing secret, and a
     * build published without that step forwards anonymously — so a harness 0.1.2 or later answers
     * 401 to every proxied call, including both mux upgrades, and the phone shows a working
     * connection that never produces a session.
     *
     * Separated from [PairingRequired] because the fixes point at different machines, and pairing
     * again — the move that message invites — changes nothing here: the device token was never the
     * problem. dsh-relay 0.2.1 reached the npm registry without its session signing; installing it
     * from source, or any release that carries the fix, is what ends this.
     */
    data object RelayUnauthenticated : ConnectFailure

    /**
     * The relay's key is not the one pinned at pairing.
     *
     * Never silently retried and never downgraded to plain CA validation. The benign cause — the
     * relay regenerated its certificate after its address set changed — and the alarming one look
     * identical from here, so the only honest move is to say the key changed and let the user decide
     * whether to pair again.
     */
    data object CertificateChanged : ConnectFailure

    /** The name did not resolve on this network. */
    data object DnsFailure : ConnectFailure

    /** Something is listening, but it is not a harness. */
    data object NotAHarness : ConnectFailure

    /** The TLS handshake failed — an untrusted certificate, or `https://` to a plain-HTTP server. */
    data object TlsFailure : ConnectFailure

    /** The API answered but the event streams would not open. */
    data object StreamsBlocked : ConnectFailure

    /** Anything else; [detail] is the carrier's own words. */
    data class Other(val detail: String) : ConnectFailure

    companion object {

        /**
         * Map a pre-flight probe outcome.
         *
         * [relay] is what the app knows locally about the address — that it is a relay this device
         * has paired with. It is the only thing that separates a 403 meaning "pair again" from one
         * meaning "add this address to the harness's trusted hosts", because the two arrive as the
         * same status with no body a WebSocket upgrade could carry.
         */
        fun from(outcome: ProbeOutcome, relay: Boolean = false): ConnectFailure = when (outcome) {
            is ProbeOutcome.Reachable -> Other("")
            ProbeOutcome.PairingRequired -> PairingRequired
            ProbeOutcome.CertificateChanged -> CertificateChanged
            ProbeOutcome.TrustFence -> if (relay) PairingRequired else TrustFence
            // A relay answers 403, never 401, so a 401 behind one is the harness refusing the
            // relay's own upstream request — not this device's token. The probe says so itself;
            // the branch below still covers a 401 that reaches here with a relay in front without
            // having been through `DiscoveryEngine.probeOutcome`.
            ProbeOutcome.RelayUnauthenticated -> RelayUnauthenticated
            ProbeOutcome.Unauthenticated -> if (relay) RelayUnauthenticated else Unauthenticated
            ProbeOutcome.Refused -> Refused
            ProbeOutcome.Timeout -> Timeout
            ProbeOutcome.DnsFailure -> DnsFailure
            // No route is a different-network problem; the subnet pre-check catches most of these
            // first, and when it does not, "nothing answered" is the honest reading.
            ProbeOutcome.Unreachable -> Timeout
            ProbeOutcome.NotAHarness -> NotAHarness
            ProbeOutcome.TlsFailure -> TlsFailure
            is ProbeOutcome.Other -> Other(outcome.detail)
        }

        /** Map a failure from inside the connection loop's readiness handshake. */
        fun from(failure: GenerationFailure, relay: Boolean = false): ConnectFailure = when (failure) {
            is GenerationFailure.MuxTimedOut -> StreamsBlocked
            is GenerationFailure.MuxFailed -> fromKind(failure.kind, failure.message, StreamsBlocked, relay)
            // The ready frame replaced `host.describe` as the last handshake step, so this is where
            // "reached it, could not finish" now lands.
            is GenerationFailure.ReadyFailed -> fromKind(
                TransportFailures.of(failure.error),
                failure.error.message,
                Other(failure.error.message),
                relay,
            )
        }

        private fun fromKind(
            kind: TransportFailure?,
            message: String?,
            fallback: ConnectFailure,
            relay: Boolean = false,
        ): ConnectFailure = when (kind) {
            TransportFailure.CERTIFICATE_PIN -> CertificateChanged
            TransportFailure.TRUST_FENCE -> if (relay) PairingRequired else TrustFence
            TransportFailure.UNAUTHENTICATED -> if (relay) RelayUnauthenticated else Unauthenticated
            TransportFailure.REFUSED -> Refused
            TransportFailure.TIMEOUT, TransportFailure.UNREACHABLE -> Timeout
            TransportFailure.DNS -> DnsFailure
            // A no-argument call cannot be refused as too large, so 413 is something that is not
            // the harness. A throttle and a dead upstream, by contrast, are real harness addresses
            // and carry wording that says so, so they keep their message instead of being flattened
            // into "not a harness" and sending the person to re-check an address that was right.
            TransportFailure.NOT_FOUND, TransportFailure.NOT_A_HARNESS, TransportFailure.TOO_LARGE -> NotAHarness
            TransportFailure.TLS -> TlsFailure
            TransportFailure.RATE_LIMITED, TransportFailure.UPSTREAM_DOWN, TransportFailure.OTHER ->
                message?.takeIf { it.isNotBlank() }?.let { Other(it) } ?: fallback
            null -> fallback
        }
    }
}
